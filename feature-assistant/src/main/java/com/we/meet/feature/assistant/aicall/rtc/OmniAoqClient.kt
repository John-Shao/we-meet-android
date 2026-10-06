package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.os.SystemClock
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import android.util.Log
import android.view.View
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import com.we.meet.feature.assistant.aicall.model.AiCallSetupException
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** AOQ media connects directly to Aliyun; our backend allocates credentials. */
class OmniAoqClient(
    private val context: Context,
    private val onAudioLevel: (Float) -> Unit,
    private val onFailure: () -> Unit,
    onTranscript: (AssistantHistoryRow) -> Unit = {},
) : OmniCallClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackDiagnostics = OmniPlaybackDiagnostics(context, "AOQ", "decoded_pcm_rms")
    private val transcript = OmniTranscript(onTranscript)
    private val ready = CompletableDeferred<Unit>()
    private val recovery = OmniConnectionRecovery(scope, ::fail)
    private val cancellation = OmniCancellation(SystemClock::elapsedRealtime)
    private var engine: AoqClientEngine? = null
    private var answer: AiCallAnswer? = null
    private var configured = false
    @Volatile private var closed = false
    @Volatile private var lastLevelAt = 0L
    private var speechEndedAt: Long? = null
    private var cameraStarted = false
    private var responding = false
    private var outputMuted = false
    override var cameraFront = false
        private set
    private val audio = AoqTrackType.AoqTrackTypeAudio
    private val video = AoqTrackType.AoqTrackTypeVideo
    private val listener = object : AoqClientListener() {
        override fun onError(code: Int, message: String?) = dispatch {
            Log.w("OmniAoq", "SDK error code=$code")
            fail()
        }
        override fun onConnectionStatusChange(status: AoqConnectionStatus) = dispatch {
            Log.i("OmniAoq", "Connection state: $status")
            when (status) {
                AoqConnectionStatus.AoqConnectionStatusConnected -> {
                    recovery.connected()
                    if (!configured) configure()
                }
                AoqConnectionStatus.AoqConnectionStatusDisconnected -> recovery.disconnected()
                AoqConnectionStatus.AoqConnectionStatusFailed -> fail()
                else -> Unit
            }
        }
        override fun onDataMsg(msg: AoqDataMsg) = dispatch {
            val event = JSONObject(String(msg.data, Charsets.UTF_8))
            transcript.accept(event)
            Log.d("OmniAoq", "Event: ${event.optString("type")}")
            when (event.optString("type")) {
                "session.updated" -> if (configured && !ready.isCompleted) ready.complete(Unit)
                "input_audio_buffer.speech_stopped" -> { speechEndedAt = SystemClock.elapsedRealtime() }
                "response.created" -> { responding = true }
                "response.done" -> { responding = false; onAudioLevel(0f) }
                "input_audio_buffer.speech_started" -> {
                    ok(engine!!.interruptAudioPlayer(audio, 100)); onAudioLevel(0f)
                }
                "error" -> {
                    val error = event.optJSONObject("error")
                    Log.w("OmniAoq", "Session error code=${error?.optString("code")}")
                    if (error == null || !ready.isCompleted || !cancellation.recoverable(
                            error.optString("type"), error.optString("code"), error.optString("message"),
                            error.optString("event_id"), error.optString("param"))) fail()
                }
            }
        }
    }

    override suspend fun connect(exchange: suspend (String) -> AiCallAnswer) {
        if (!Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }) {
            throw AiCallSetupException(R.string.assistant_error_aoq_native, "unsupported_abi")
        }
        // A mixed-ABI APK can run as x86 even on an emulator with ARM translation.
        // Verify the library for this process before allocating a provider session.
        try {
            System.loadLibrary("aoq_client_sdk")
        } catch (error: UnsatisfiedLinkError) {
            throw AiCallSetupException(R.string.assistant_error_aoq_native, "native_library", error)
        }
        val startedAt = SystemClock.elapsedRealtime()
        withTimeout(60_000) {
            Log.i("OmniAoq", "Requesting connection allocation")
            answer = exchange("")
            check(!closed)
            val credentials = checkNotNull(answer!!.aoq) { "AOQ allocation missing" }
            synchronized(ownership) {
                check(owner == null) { "AOQ engine already in use" }
                owner = this@OmniAoqClient
            }
            try {
                engine = checkNotNull(AoqClientEngine.createEngine(context, AoqCreateConfig().apply {
                    workDir = context.filesDir.absolutePath
                }, listener))
            } catch (error: LinkageError) {
                throw AiCallSetupException(R.string.assistant_error_aoq_native, "native_engine", error)
            }
            val sdk = engine!!
            ok(sdk.enableSendMediaStream(audio, false))
            ok(sdk.enableSendMediaStream(video, false))
            ok(sdk.setAudioEncoderConfig(AoqAudioCodecConfig().apply {
                codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 16000; channel = 1
            }))
            ok(sdk.setAudioDecoderConfig(AoqAudioCodecConfig().apply {
                codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 24000; channel = 1
            }))
            ok(sdk.setAudioFrameObserver(object : AoqClientListener.AoqAudioFrameListener {
                override fun onPlaybackAudioFrame(frame: AoqAudioFrameData) {
                    val now = SystemClock.elapsedRealtime()
                    if (closed || now - lastLevelAt < 100 || frame.bytesPerSample != 2) return
                    lastLevelAt = now
                    val bytes = frame.dataPtr ?: return
                    val size = minOf(frame.dataSize, bytes.size)
                    if (size < 2) return
                    val pcm = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                    var squares = 0.0
                    var count = 0
                    while (pcm.remaining() >= 2) {
                        val value = pcm.short.toDouble() / 32768.0
                        squares += value * value; count++
                    }
                    val level = sqrt(squares / count).toFloat()
                    dispatch {
                        if (!outputMuted) playbackDiagnostics.sample(level)
                        onAudioLevel(if (outputMuted) 0f else level)
                        if (level > 0.01f && !outputMuted) {
                            speechEndedAt?.let { Log.i("OmniAoq", "First playback after speech end: ${now - it} ms") }
                            speechEndedAt = null
                        }
                    }
                }
            }))
            ok(sdk.enableAudioFrameObserver(true, AoqAudioSource.AoqAudioSourcePlayback, AoqAudioObserverConfig().apply {
                sampleRate = 24000; channels = 1; mode = AoqAudioObserverMode.AoqAudioObserverModeReadOnly
            }))
            val config = AoqConnectConfig().apply {
                token = credentials.aoqTokenForClient; sid = credentials.sid
                certFingerprint = credentials.clientRelayCertFingerprint
                workspaceIdHash = credentials.workspaceIdHash
                credentials.clientRelayEndpoints.forEachIndexed { index, relay ->
                    relayEndpoints.add(AoqRelayEndpoint().apply {
                        endpoint = relay.endpoint; port = relay.port; routeIndex = relay.route_index ?: index
                    })
                }
                listOf(audio, video, AoqTrackType.AoqTrackTypeData).forEach { type ->
                    publishTracks.add(AoqTrackParam().apply { trackType = type })
                }
                listOf(audio, AoqTrackType.AoqTrackTypeData).forEach { type ->
                    subscribeTracks.add(AoqTrackParam().apply { trackType = type })
                }
            }
            ok(sdk.connect(config))
            withTimeout(25_000) { ready.await() }
            check(!closed)
            Log.i("OmniAoq", "Playback mode: ${if (AoqPlaybackMode.media) "media" else "voip"}")
            ok(sdk.startAudioPlayer(AoqAudioPlaybackConfig().apply {
                channel = 1; isExternal = false; isDefaultSpeaker = true; isVoipMode = AoqPlaybackMode.voip
            }))
            ok(sdk.startAudioCapture(AoqAudioCaptureConfig().apply { channel = 1; isExternal = false }))
            // Capture can reinitialize the SDK audio manager after the player config.
            // Reassert its speaker preference after both devices are initialized;
            // the SDK continues to manage focus and connected headset routing.
            ok(sdk.enableSpeakerphone(true))
            ok(sdk.enableSendMediaStream(audio, true))
            Log.i("OmniAoq", "Session ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    private fun configure() {
        val config = checkNotNull(answer)
        configured = true
        // Match the existing WebRTC baseline's prompt and VAD configuration.
        send(JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("modalities", JSONArray(listOf("text", "audio")))
            .put("input_audio_format", "pcm").put("output_audio_format", "pcm")
            .put("voice", config.voice).put("instructions", config.instructions)
            .put("input_audio_transcription", JSONObject().put("model", "qwen3-asr-flash-realtime"))
            .put("turn_detection", JSONObject().put("type", "server_vad")
                .put("threshold", 0.5).put("silence_duration_ms", 800))))
    }
    override fun setMicrophoneEnabled(enabled: Boolean) { ok(engine!!.muteAudioCapture(!enabled)) }
    override fun setOutputMuted(muted: Boolean) {
        ok(if (muted) engine!!.pauseAudioPlayer(0) else engine!!.resumeAudioPlayer(0))
        outputMuted = muted
        if (muted) onAudioLevel(0f)
    }
    override fun interrupt() {
        ok(engine!!.interruptAudioPlayer(audio, 100))
        if (responding) {
            val id = UUID.randomUUID().toString()
            send(JSONObject().put("type", "response.cancel").put("event_id", id))
            cancellation.sent(id); responding = false
        }
        onAudioLevel(0f)
    }
    override fun setCameraEnabled(enabled: Boolean) {
        if (enabled == cameraStarted) return
        val sdk = checkNotNull(engine)
        if (enabled) {
            ok(sdk.startVideoCapture(AoqVideoCaptureConfig().apply {
                width = 1280; height = 720; fps = 2; isExternal = false
                cameraDirection = if (cameraFront) AoqCameraDirection.AoqCameraDirectionFront else AoqCameraDirection.AoqCameraDirectionBack
            }))
            ok(sdk.enableSendMediaStream(video, true))
        } else {
            ok(sdk.enableSendMediaStream(video, false)); ok(sdk.stopVideoCapture())
        }
        cameraStarted = enabled
    }
    override suspend fun flipCamera(): Boolean {
        check(cameraStarted)
        val front = !cameraFront
        ok(engine!!.switchCamera(if (front) AoqCameraDirection.AoqCameraDirectionFront else AoqCameraDirection.AoqCameraDirectionBack))
        cameraFront = front
        return front
    }
    fun attachPreview(view: View) {
        ok(engine!!.setLocalView(video, AoqVideoCanvas().apply { this.view = view; renderMode = AoqRenderMode.AoqRenderModeCrop }))
    }
    fun detachPreview() { if (!closed) engine?.setLocalView(video, AoqVideoCanvas()) }
    private fun send(event: JSONObject) {
        if (!event.has("event_id")) event.put("event_id", UUID.randomUUID().toString())
        ok(engine!!.sendDataMsg(AoqDataMsg().apply { data = event.toString().toByteArray(Charsets.UTF_8) }))
    }
    private fun ok(result: Int) { check(result == 0) { "AOQ operation failed ($result)" } }
    private fun dispatch(block: () -> Unit) { scope.launch { if (!closed) runCatching(block).onFailure { fail() } } }
    private fun fail() {
        if (closed) return
        ready.completeExceptionally(IllegalStateException("AOQ connection failed"))
        onFailure()
    }
    override fun close() {
        if (closed) return
        closed = true
        ready.cancel(); recovery.close(); scope.cancel()
        synchronized(ownership) {
            if (owner === this) {
                runCatching { engine?.enableSendMediaStream(audio, false) }
                runCatching { engine?.enableSendMediaStream(video, false) }
                runCatching { engine?.stopVideoCapture() }
                runCatching { engine?.stopAudioCapture() }
                runCatching { engine?.stopAudioPlayer() }
                runCatching { engine?.disconnect() }
                runCatching { AoqClientEngine.destroy() }
                owner = null
            }
        }
        engine = null

    }
    private companion object {
        val ownership = Any()
        var owner: OmniAoqClient? = null
    }
}
