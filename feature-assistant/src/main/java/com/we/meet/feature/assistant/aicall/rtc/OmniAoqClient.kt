package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import android.util.Log
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings
import com.we.meet.feature.assistant.aicall.model.AiCallSetupException
import com.we.meet.feature.assistant.aicall.model.ScreenShareSizing
import com.we.meet.feature.assistant.aicall.model.CameraToolHandler
import com.we.meet.feature.assistant.aicall.model.CameraActionResult
import com.we.meet.feature.assistant.aicall.model.CameraFeedbackFailure
import com.we.meet.feature.assistant.aicall.model.PhotoToolHandler
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
    toolHandler: CameraToolHandler? = null,
    onToolFeedbackFailure: (CameraFeedbackFailure) -> Unit = {},
    onEndCall: (() -> Unit)? = null,
    photoHandler: PhotoToolHandler? = null,
    private val screenReadHandler: PhotoToolHandler? = null,
    private val vadMode: AiCallVadMode = AiCallVadMode.Server,
    private val videoSettings: AiCallVideoSettings = AiCallVideoSettings(),
) : OmniCallClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackDiagnostics = OmniPlaybackDiagnostics(context, "AOQ", "decoded_pcm_rms")
    private val audioDiagnostics = AoqAudioDiagnostics(SystemClock::elapsedRealtime)
    private val transcript = OmniTranscript(onTranscript)
    private val photos = CallPhotoCapture(context)
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
    private var captureActive = false
    private var cameraCertain = true
    private val camera = AoqCameraCapture(context, { frame ->
        ok(checkNotNull(engine).pushExternalVideoCapturedFrame(video, frame))
    }, { dispatch { if (cameraStarted) fail() } }, videoSettings)
    val eglContext: EglBase.Context? get() = camera.eglContext
    override val cameraEnabled: Boolean? get() = if (cameraCertain) cameraStarted else null

    // Screen frames share the single AOQ video track with the camera. They are
    // pushed as raw I420 through the same external-frame API.
    private var screenCapture: ScreenShareCapture? = null
    private var screenEgl: EglBase? = null
    private var screenRouter: CameraFrameRouter? = null
    private val screenFrames = ScreenFrameStore()
    private var screenStarted = false
    private var screenStopCallback: (() -> Unit)? = null
    private var pendingScreenShareUpdate: Pair<Boolean, Boolean>? = null
    private val screenUpdateEventIds = linkedSetOf<String>()
    override val screenShareAvailable: Boolean get() = true
    override val screenSharing: Boolean get() = screenStarted
    private var responding = false
    @Volatile private var outputMuted = false
    @Volatile private var toolOutputHeld = false
    @Volatile private var outputSuppressed = false
    private val cameraToolHandler = toolHandler?.takeIf { com.we.meet.feature.assistant.BuildConfig.AI_CALL_CAMERA_VOICE_CONTROL }
    private val endCallHandler = onEndCall?.takeIf { com.we.meet.feature.assistant.BuildConfig.AI_CALL_VOICE_HANGUP }
    private val tools = if (cameraToolHandler != null || endCallHandler != null || photoHandler != null || screenReadHandler != null) {
        OmniCallTools(scope, cameraToolHandler, ::send, { held ->
            // Keep decoding/draining the SDK stream; pausing the player can
            // defer response.done until playback resumes, deadlocking a tool.
            toolOutputHeld = held
        }, onToolFeedbackFailure, { code -> CameraActionResult(false, cameraEnabled, false, code,
            context.getString(R.string.assistant_call_invalid_tool)) }, endCallHandler, allowMissingResponseDone = true, photoHandler = photoHandler,
            screenReadHandler = screenReadHandler, logger = { Log.i("OmniTools", it) })
    } else null
    override var cameraFront = false
        private set
    private val audio = AoqTrackType.AoqTrackTypeAudio
    private val video = AoqTrackType.AoqTrackTypeVideo
    private val listener = object : AoqClientListener() {
        override fun onWarning(code: Int, message: String?) {
            // Do not log SDK text: it can contain provider/session details.
            Log.w("OmniAoqAudio", "SDK warning code=$code")
        }
        override fun onAudioDeviceRouteChanged(routeType: Int) {
            Log.i("OmniAoqAudio", "Route type=$routeType")
        }
        override fun onAudioDeviceInterrupted(interrupt: Boolean) {
            Log.i("OmniAoqAudio", "Device interrupted=$interrupt")
        }
        override fun onAudioDeviceFocusChanged(focusChange: Int) {
            Log.i("OmniAoqAudio", "Focus change=$focusChange")
        }
        override fun onStats(stats: AoqStats) {
            if (closed) return
            audioDiagnostics.update(stats)?.let { quality -> dispatch {
                Log.i("OmniAoqAudio", "Quality camera=$cameraStarted responding=$responding " +
                    "muted=$outputMuted held=$toolOutputHeld suppressed=$outputSuppressed voip=${AoqPlaybackMode.voip} $quality")
            } }
            if (com.we.meet.feature.assistant.BuildConfig.DEBUG && cameraStarted) {
                stats.videoPublishStats?.forEach {
                    Log.d("OmniAoqCamera", "Video encodeFps=${it.encodeFps} bitrate=${it.bitrate} bytes=${it.bytes}")
                }
            }
        }
        override fun onVideoDeviceStateChanged(state: AoqVideoDeviceState) = dispatch {
            when (state.state) {
                AoqVideoDeviceStateCode.AoqVideoDeviceCaptureFail -> {
                    if (cameraStarted) fail()
                }
                else -> Unit
            }
        }
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
            tools?.accept(event)
            transcript.accept(event, tools?.suppressesAssistant(event) == true)
            Log.d("OmniAoq", "Event: ${event.optString("type")}")
            when (event.optString("type")) {
                "session.updated" -> if (configured && !ready.isCompleted) ready.complete(Unit)
                "input_audio_buffer.speech_stopped" -> { speechEndedAt = SystemClock.elapsedRealtime() }
                "response.created" -> { responding = true; outputSuppressed = false }
                "response.done" -> { responding = false; onAudioLevel(0f); flushScreenShareState() }
                "input_audio_buffer.speech_started" -> {
                    // A server VAD event, not proof of user speech or acoustic echo.
                    // Keep only timing/state metadata, never PCM or transcripts.
                    Log.i("OmniAoqAudio", "VAD speech_started camera=$cameraStarted responding=$responding " +
                        "muted=$outputMuted held=$toolOutputHeld suppressed=$outputSuppressed " +
                        "voip=${AoqPlaybackMode.voip} ${audioDiagnostics.snapshot()}")
                    // Refresh before generation, not during a tool continuation.
                    // Button changes and older tool results must not leave a stale snapshot.
                    publishCameraState()
                    if (tools == null) ok(engine!!.interruptAudioPlayer(audio, 100))
                    else outputSuppressed = true
                    onAudioLevel(0f)
                }
                "error" -> {
                    val error = event.optJSONObject("error")
                    Log.w("OmniAoq", "Session error type=${error?.optString("type")} code=${error?.optString("code")} param=${error?.optString("param")}")
                    if (error != null && screenUpdateEventIds.remove(error.optString("event_id"))) {
                        Log.w("OmniAoq", "Screen share state update rejected; retaining call")
                        return@dispatch
                    }
                    val rejectedVideoFrame = error != null && ready.isCompleted && OmniMediaErrors.isImageBeforeAudio(
                        error.optString("type"), error.optString("code"), error.optString("message"),
                        error.optString("event_id"), error.optString("param"))
                    // AOQ audio/video tracks can arrive out of order after a VAD
                    // commit. Discard this rejected frame; do not replay it or
                    // tear down an otherwise healthy call. Other errors remain fatal.
                    if (rejectedVideoFrame) Log.w("OmniAoq", "Video frame rejected before new audio; continuing session")
                    if (error == null || !ready.isCompleted || (!rejectedVideoFrame && tools?.recoverableError(error) != true && !cancellation.recoverable(
                            error.optString("type"), error.optString("code"), error.optString("message"),
                            error.optString("event_id"), error.optString("param")))) fail()
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
            tools?.configureInstructions(answer!!.tool_instructions)
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
                    if (closed) return
                    audioDiagnostics.frameReceived()
                    if (tools != null && (outputMuted || toolOutputHeld || outputSuppressed)) {
                        frame.dataPtr?.fill(0)
                        dispatch { onAudioLevel(0f) }
                        return
                    }
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
                    if (level > 0.01f && !outputMuted && !toolOutputHeld && !outputSuppressed) {
                        audioDiagnostics.audiblePlayback()
                    }
                    dispatch {
                        if (!outputMuted && !toolOutputHeld && !outputSuppressed) playbackDiagnostics.sample(level)
                        onAudioLevel(if (outputMuted || toolOutputHeld || outputSuppressed) 0f else level)
                        if (level > 0.01f && !outputMuted && !toolOutputHeld && !outputSuppressed) {
                            tools?.playback()
                            speechEndedAt?.let { Log.i("OmniAoq", "First playback after speech end: ${now - it} ms") }
                            speechEndedAt = null
                        }
                    }
                }
            }))
            ok(sdk.enableAudioFrameObserver(true, AoqAudioSource.AoqAudioSourcePlayback, AoqAudioObserverConfig().apply {
                sampleRate = 24000; channels = 1
                mode = if (tools != null) AoqAudioObserverMode.AoqAudioObserverModeReadWrite else AoqAudioObserverMode.AoqAudioObserverModeReadOnly
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
            ok(sdk.startAudioPlayer(AoqPlaybackMode.playbackConfig()))
            ok(sdk.startAudioCapture(AoqPlaybackMode.captureConfig()))
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
        Log.i("OmniCall", "transport=AOQ vad=${vadMode.wireValue}")
        send(JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("modalities", JSONArray(listOf("text", "audio")))
            .put("input_audio_format", "pcm").put("output_audio_format", "pcm")
            .put("voice", config.voice).put("instructions", tools?.instructions(config.instructions) ?: config.instructions)
            .put("input_audio_transcription", JSONObject().put("model", "qwen3-asr-flash-realtime"))
            .put("turn_detection", vadMode.turnDetection())
            .apply { if (tools != null) {
                put("tools", tools.definitions()); put("enable_search", false)
                put("temperature", 0.0); put("presence_penalty", 0.0)
            } }))
    }
    override fun setMicrophoneEnabled(enabled: Boolean) { ok(engine!!.muteAudioCapture(!enabled)) }
    override fun publishCameraState() {
        if (!screenStarted) answer?.let { tools?.publishState(it.instructions, cameraEnabled) }
    }

    override fun publishScreenShareState(sharing: Boolean, visible: Boolean) {
        if (closed) return
        pendingScreenShareUpdate = sharing to visible
        if (!responding) flushScreenShareState()
    }

    private fun flushScreenShareState() {
        val update = pendingScreenShareUpdate ?: return
        pendingScreenShareUpdate = null
        val config = answer ?: return
        val (sharing, visible) = update
        val instructions = tools?.instructions(config.instructions, sharing, visible)
            ?: OmniCallTools.screenShareInstructions(config.instructions, sharing, visible)
        val session = JSONObject().put("instructions", instructions)
        if (tools != null) session.put("tools", tools.definitions(sharing, visible))
        val eventId = UUID.randomUUID().toString()
        val event = JSONObject().put("type", "session.update").put("session", session).put("event_id", eventId)
        if (screenUpdateEventIds.size > 32) screenUpdateEventIds.remove(screenUpdateEventIds.first())
        screenUpdateEventIds.add(eventId)
        runCatching { send(event) }.onFailure {
            screenUpdateEventIds.remove(eventId)
            Log.w("OmniAoq", "Screen share state sync failed")
        }
    }
    override fun setOutputMuted(muted: Boolean) {
        outputMuted = muted
        if (tools == null) ok(if (muted) engine!!.pauseAudioPlayer(0) else engine!!.resumeAudioPlayer(0))
        if (muted) onAudioLevel(0f)
    }
    override fun interrupt() {
        tools?.interrupted()
        if (tools == null) ok(engine!!.interruptAudioPlayer(audio, 100))
        else outputSuppressed = true
        if (responding) {
            val id = UUID.randomUUID().toString()
            cancellation.sent(id)
            send(JSONObject().put("type", "response.cancel").put("event_id", id))
            responding = false
        }
        flushScreenShareState()
        onAudioLevel(0f)
    }
    override suspend fun setScreenShareEnabled(
        enabled: Boolean,
        resultData: Intent?,
        onSystemStop: (() -> Unit)?,
        onVisibilityChanged: ((Boolean) -> Unit)?,
    ) {
        check(!closed)
        if (!enabled) {
            if (!screenStarted && screenCapture == null) return
            screenStopCallback = null
            screenRouter?.stop()
            screenRouter = null
            runCatching { engine?.enableSendMediaStream(video, false) }
            val capture = screenCapture
            screenCapture = null
            if (capture != null) withContext(Dispatchers.IO) { capture.stop() }
            runCatching { engine?.stopVideoCapture() }
            runCatching { screenEgl?.release() }
            screenEgl = null
            screenFrames.clear()
            // Keep screenStarted true until capture/encoder resources are gone.
            screenStarted = false
            return
        }
        check(!screenStarted && screenCapture == null) { "Screen sharing already active" }
        check(!cameraStarted) { "Stop the camera before sharing the screen" }
        val consent = checkNotNull(resultData) { "MediaProjection consent missing" }
        val visibilityListener = onVisibilityChanged
        val sdk = checkNotNull(engine)
        val size = ScreenShareSizing.forDisplay(context)
        val uploadFps = ScreenShareSizing.modelUploadFps(videoSettings.modelUploadFps)
        Log.i("OmniScreen", "AOQ screen uploadFps=$uploadFps")
        // Screen frames are converted with WebRTC's YuvHelper, which needs the
        // native library even when this call never opens the camera.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val egl = EglBase.create()
        val firstFrame = CompletableDeferred<Unit>()
        var router: CameraFrameRouter? = null
        var capture: ScreenShareCapture? = null
        try {
            ok(sdk.setVideoEncoderConfig(AoqVideoCodecConfig().apply {
                trackType = video
                codecType = AoqEncoderType.AoqEncoderTypeVideoH264
                width = size.width
                height = size.height
                fps = uploadFps
                bitrate = 800_000
                minBitrate = 200_000
                keyframeInterval = 2
                isExternal = false // SDK encodes raw external frames.
            }))
            ok(sdk.startVideoCapture(AoqVideoCaptureConfig().apply { isExternal = true }))
            val frames = CameraFrameRouter(
                previewFps = videoSettings.localPreviewFps,
                uploadFps = uploadFps,
            ) { frame ->
                runCatching {
                    ok(sdk.pushExternalVideoCapturedFrame(video, AoqVideoFramePacker.pack(frame)))
                }.onSuccess {
                    firstFrame.complete(Unit)
                }.onFailure {
                    firstFrame.completeExceptionally(it)
                }
            }
            router = frames
            frames.start()
            val screen = ScreenShareCapture(
                context = context,
                eglContext = egl.eglBaseContext,
                onSystemStop = {
                    // Runs on the capture thread; never stop the capturer here.
                    dispatch {
                        if (!closed && screenStarted) {
                            screenStopCallback?.invoke()
                        }
                    }
                },
                onContentResize = { width, height ->
                    // AOQ's encoder was configured for the initial canvas;
                    // resizing it mid-stream requires restarting the encoder.
                    // Keep that canvas and let the SDK scale the content.
                    Log.i("OmniScreen", "AOQ resize ${width}x$height ignored; fixed canvas ${size.width}x${size.height}")
                },
                onVisibilityChanged = { visible ->
                    dispatch {
                        if (!closed && screenStarted) {
                            if (visible) screenRouter?.start() else screenRouter?.stop()
                            publishScreenShareState(true, visible)
                            visibilityListener?.invoke(visible)
                        }
                    }
                },
            )
            capture = screen
            screen.start(consent, object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) {
                    if (!success) firstFrame.completeExceptionally(IllegalStateException("Screen capture failed"))
                }

                override fun onCapturerStopped() = Unit
                override fun onFrameCaptured(frame: VideoFrame) {
                    screenFrames.update(frame)
                    frames.onFrame(frame)
                }
            }, size.width, size.height, ScreenShareSizing.CAPTURE_FPS)
            withTimeout(8_000) { firstFrame.await() }
            check(!closed)
            ok(sdk.enableSendMediaStream(video, true))
        } catch (error: Exception) {
            router?.stop()
            capture?.stop()
            runCatching { sdk.enableSendMediaStream(video, false) }
            runCatching { sdk.stopVideoCapture() }
            egl.release()
            throw error
        }
        screenCapture = capture
        screenEgl = egl
        screenRouter = router
        screenStopCallback = onSystemStop
        screenStarted = true
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        check(!closed)
        if (enabled) check(!screenStarted && screenCapture == null) { "Stop screen sharing before opening the camera" }
        if (cameraCertain && enabled == cameraStarted && (enabled || !captureActive)) return
        val sdk = checkNotNull(engine)
        if (enabled) {
            cameraCertain = false
            ok(sdk.setVideoEncoderConfig(AoqVideoCodecConfig().apply {
                trackType = video; codecType = AoqEncoderType.AoqEncoderTypeVideoH264
                width = 1280; height = 720; fps = videoSettings.modelUploadFps; bitrate = 500_000; minBitrate = 128_000; keyframeInterval = 2
                isExternal = false // SDK encodes raw external frames.
            }))
            captureActive = true
            ok(sdk.startVideoCapture(AoqVideoCaptureConfig().apply { isExternal = true }))
            camera.start(cameraFront)
            check(!closed)
            ok(sdk.enableSendMediaStream(video, true))
            cameraStarted = true; cameraCertain = true
        } else {
            cameraCertain = false
            // Drain frame submissions before disabling the SDK video stream.
            // A late push after disable can fail and close the entire call.
            camera.stopFrames()
            ok(sdk.enableSendMediaStream(video, false))
            camera.stop() // No new frames can be submitted; actual Camera2 close confirmed.
            if (captureActive) ok(sdk.stopVideoCapture())
            captureActive = false; cameraStarted = false; cameraCertain = true
        }
    }
    override suspend fun flipCamera(): Boolean {
        check(cameraStarted)
        return camera.flip().also { cameraFront = it }
    }
    fun attachPreview(sink: VideoSink) { if (!closed) camera.attachPreview(sink) }
    fun detachPreview(sink: VideoSink) = camera.detachPreview(sink)
    override suspend fun capturePhoto(): ByteArray {
        check(!closed)
        val jpeg = withPhotoVideoPause(cameraStarted, { !closed }, pause = {
            camera.stopFrames()
            ok(checkNotNull(engine).enableSendMediaStream(video, false))
            camera.stop()
        }, resume = {
            camera.start(cameraFront)
            check(!closed)
            ok(checkNotNull(engine).enableSendMediaStream(video, true))
        }) { photos.capture(front = if (cameraStarted) cameraFront else false) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(!closed)
        transcript.photo(jpeg)
        return jpeg
    }

    override suspend fun captureScreenPhoto(): ByteArray {
        check(!closed && screenStarted)
        val generation = screenFrames.currentGeneration()
        // A static screen may not emit a new frame promptly. Prefer a fresh
        // one, but still read the latest available frame if it does not.
        withTimeoutOrNull(750) {
            while (screenFrames.currentGeneration() == generation) delay(50)
        }
        // The tool can arrive immediately after sharing starts, before the
        // foreground app's first stable frame; sample after a short settle.
        delay(1_000)
        val raw = withContext(Dispatchers.Default) {
            screenFrames.encodeLatest() ?: error("No screen frame available")
        }
        val jpeg = withContext(Dispatchers.Default) { CallPhotoImage.encode(raw) }
        return jpeg
    }

    override fun displayScreenPhoto(jpeg: ByteArray) {
        transcript.screen(jpeg)
    }
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
        runCatching { photos.close() }
        screenStarted = false
        screenStopCallback = null
        runCatching { screenRouter?.stop() }
        screenRouter = null
        runCatching { screenCapture?.stop() }
        screenCapture = null
        runCatching { screenEgl?.release() }
        screenEgl = null
        screenFrames.clear()
        runCatching { camera.close() }
        ready.cancel(); recovery.close(); tools?.close(); scope.cancel()
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
