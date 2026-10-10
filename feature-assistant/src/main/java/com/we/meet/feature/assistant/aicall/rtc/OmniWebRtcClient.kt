package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import com.we.meet.feature.assistant.aicall.model.CameraToolHandler
import com.we.meet.feature.assistant.aicall.model.CameraActionResult
import com.we.meet.feature.assistant.aicall.model.CameraFeedbackFailure
import com.we.meet.feature.assistant.aicall.model.PhotoToolHandler
import com.we.meet.feature.assistant.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native WebRTC media goes directly to Omni; only SDP passes through our backend. */
class OmniWebRtcClient(
    private val context: Context,
    private val onAudioLevel: (Float) -> Unit,
    private val onFailure: () -> Unit,
    private val onTranscript: (com.we.meet.feature.assistant.history.AssistantHistoryRow) -> Unit = {},
    toolHandler: CameraToolHandler? = null,
    onToolFeedbackFailure: (CameraFeedbackFailure) -> Unit = {},
    onEndCall: (() -> Unit)? = null,
    photoHandler: PhotoToolHandler? = null,
) : OmniCallClient {
    private val playbackDiagnostics = OmniPlaybackDiagnostics(context, "WebRTC", "inbound_rtp_audio_level")
    private val transcript = OmniTranscript(onTranscript)
    private val photos = CallPhotoCapture(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handshake = OmniHandshake()
    private val recovery = OmniConnectionRecovery(scope) { fail("connection_recovery_timeout") }
    private val cancellation = OmniCancellation(SystemClock::elapsedRealtime)
    private val responseCreation = OmniResponseCreation(SystemClock::elapsedRealtime)
    private val iceComplete = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val channels = mutableSetOf<DataChannel>()
    private val cameraFrames = CameraFrameRouter { videoSource?.capturerObserver?.onFrameCaptured(it) }
    private var eventChannel: DataChannel? = null
    private var egl: EglBase? = null
    val eglContext: EglBase.Context? get() = egl?.eglBaseContext
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioSwitch: AudioSwitch? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var videoSender: RtpSender? = null
    override val cameraAvailable: Boolean get() = videoSender != null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var cameraStarted = false
    private var captureActive = false
    private var cameraCertain = true
    private var firstVideoFrame: CompletableDeferred<Unit>? = null
    private var videoStopped: CompletableDeferred<Unit>? = null
    override val cameraEnabled: Boolean? get() = if (cameraCertain) cameraStarted else null
    override var cameraFront = false
        private set
    private var answer: AiCallAnswer? = null
    private var responding = false
    private var outputMuted = false
    private var outputSuppressed = false
    private var toolOutputHeld = false
    private val cameraToolHandler = toolHandler?.takeIf { com.we.meet.feature.assistant.BuildConfig.AI_CALL_CAMERA_VOICE_CONTROL }
    private val endCallHandler = onEndCall?.takeIf { com.we.meet.feature.assistant.BuildConfig.AI_CALL_VOICE_HANGUP }
    private val tools = if (cameraToolHandler != null || endCallHandler != null || photoHandler != null) {
        OmniCallTools(scope, cameraToolHandler, ::send, { held -> toolOutputHeld = held; updateOutput() }, onToolFeedbackFailure,
            { code -> CameraActionResult(false, cameraEnabled, false, code, context.getString(R.string.assistant_call_invalid_tool)) }, endCallHandler, photoHandler = photoHandler)
    } else null
    private fun updateOutput() { remoteAudio?.setEnabled(!outputMuted && !outputSuppressed && !toolOutputHeld) }

    override fun setOutputMuted(muted: Boolean) {
        outputMuted = muted
        updateOutput()
    }
    private var closed = false
    private var speechEndedAt: Long? = null

    override suspend fun connect(exchange: suspend (String) -> AiCallAnswer) {
        val startedAt = SystemClock.elapsedRealtime()
        withTimeout(60_000) {
            initialize()
            val pc = checkNotNull(peer)
            val offer = createOffer(pc)
            setDescription(pc, offer, local = true)
            withTimeout(15_000) { iceComplete.await() }
            answer = exchange(checkNotNull(pc.localDescription).description)
            tools?.configureInstructions(answer!!.tool_instructions)
            check(!closed)
            setDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, answer!!.sdp), local = false)
            withTimeout(25_000) { ready.await() }
            check(!closed)
            // Do not capture or send microphone data before session.updated.
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("omni-microphone", audioSource)
            check(audioSender!!.setTrack(audioTrack, false))
            startStats()
            Log.i("OmniWebRtc", "Session ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    private fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        egl = EglBase.create()
        audioSwitch = AudioSwitch(
            context,
            audioFocusChangeListener = { focus ->
                if (focus == AudioManager.AUDIOFOCUS_LOSS) dispatch { fail("audio_focus_lost") }
            },
            // Discovery is asynchronous: selecting from availableAudioDevices
            // immediately after start() sees an empty list. Keep speaker ahead
            // of earpiece for initial discovery and after a headset disconnects.
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            ),
        ).also { route ->
            route.start { _, selected ->
                Log.i("OmniAudio", "Selected output: ${selected?.javaClass?.simpleName}")
            }
            route.activate()
        }
        audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val hasH264 = encoderFactory.supportedCodecs.any { it.name.equals("H264", ignoreCase = true) }
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = checkNotNull(factory!!.createPeerConnection(config, observer))
        audioSender = peer!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
        ).sender
        // Negotiate video even for an audio-only start. Camera hardware is opened
        // only when requested; attaching/detaching the track needs no renegotiation.
        // Omni rejects the emulator's VP8/VP9-only video offer. Keep voice usable
        // on devices lacking a compatible encoder; never advertise a fake H264 codec.
        if (hasH264) videoSender = peer!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        ).sender
        if (!hasH264) Log.i("OmniWebRtc", "H264 encoder unavailable; negotiating voice only")
        bindChannel(peer!!.createDataChannel("oai-events", DataChannel.Init()))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = dispatch {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    recovery.connected()
                    handshake.connected = true
                    configureIfReady()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    handshake.connected = false
                    recovery.disconnected()
                }
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> fail("peer_${state.name.lowercase()}")
                else -> handshake.connected = false
            }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.complete(Unit)
        }
        override fun onDataChannel(channel: DataChannel) = dispatch { bindChannel(channel) }
        override fun onTrack(transceiver: RtpTransceiver) = dispatch {
            remoteAudio = transceiver.receiver.track() as? AudioTrack
            updateOutput()
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun bindChannel(channel: DataChannel) {
        channels.add(channel)
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = dispatch {
                if (channel === eventChannel) {
                    handshake.channelOpen = channel.state() == DataChannel.State.OPEN
                    if (channel.state() == DataChannel.State.CLOSED) fail("data_channel_closed")
                }
                configureIfReady()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                dispatch { handleEvent(channel, JSONObject(String(bytes, Charsets.UTF_8))) }
            }
        })
    }

    private fun handleEvent(channel: DataChannel, event: JSONObject) {
        val eventType = event.optString("type")
        if (eventType in setOf("response.created", "response.done", "response.output_item.added", "response.output_item.done",
                "response.function_call_arguments.done", "input_audio_buffer.speech_started", "error")) {
            // Metadata only: never log arguments, audio, transcripts, credentials or SDP.
            Log.i("OmniWebRtc", "Event=$eventType response=${event.optString("response_id", event.optJSONObject("response")?.optString("id").orEmpty())}")
        }
        tools?.accept(event)
        transcript.accept(event, tools?.suppressesAssistant(event) == true)
        when (event.optString("type")) {
            "session.created" -> {
                // Server-created 'txt' is supported as well as the local channel.
                eventChannel = channel
                handshake.sessionCreated = true
                handshake.channelOpen = channel.state() == DataChannel.State.OPEN
                configureIfReady()
            }
            "session.updated" -> if (handshake.acknowledge()) ready.complete(Unit)
            "input_audio_buffer.speech_stopped" -> { speechEndedAt = SystemClock.elapsedRealtime() }
            "response.created" -> {
                responseCreation.created()
                responding = true
                outputSuppressed = false
                updateOutput()
            }
            "response.done" -> responding = false
            "input_audio_buffer.speech_started" -> {
                publishCameraState()
                outputSuppressed = true; remoteAudio?.setEnabled(false)
            }
            "error" -> {
                val error = event.optJSONObject("error")
                Log.w("OmniWebRtc", "Protocol error type=${error?.optString("type")} code=${error?.optString("code")}")
                val rejected = if (error != null && handshake.ready) responseCreation.rejectedRequest(error) else null
                if (rejected != null) {
                    // The active provider response and media connection remain valid.
                    // A rejected tool continuation reports feedback failure; never retry
                    // the device operation or allocate/reconnect the whole call.
                    tools?.recoverableError(JSONObject(error!!.toString()).put("event_id", rejected))
                    Log.i("OmniWebRtc", "Overlapping response request rejected; retaining current connection")
                    return
                }
                if (error == null || !handshake.ready || (tools?.recoverableError(error) != true && !cancellation.recoverable(
                        error.optString("type"), error.optString("code"),
                        error.optString("message"), error.optString("event_id"), error.optString("param"),
                    ))) fail("protocol_error")
            }
        }
    }

    private fun configureIfReady() {
        val config = answer ?: return
        if (!handshake.takeConfiguration()) return
        send(JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("modalities", JSONArray(listOf("text", "audio")))
            .put("input_audio_format", "pcm")
            .put("output_audio_format", "pcm")
            .put("voice", config.voice)
            .put("instructions", tools?.instructions(config.instructions) ?: config.instructions)
            .put("input_audio_transcription", JSONObject().put("model", "qwen3-asr-flash-realtime"))
            .put("turn_detection", JSONObject().put("type", "server_vad")
                .put("threshold", 0.5).put("silence_duration_ms", 800))
            .apply { if (tools != null) {
                put("tools", tools.definitions()); put("enable_search", false)
                put("temperature", 0.0); put("presence_penalty", 0.0)
            } }))
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        check(!closed && audioTrack?.setEnabled(enabled) == true)
    }

    override fun interrupt() {
        tools?.interrupted()
        check(!closed && handshake.ready)
        outputSuppressed = true
        remoteAudio?.setEnabled(false)
        if (responding) {
            val eventId = UUID.randomUUID().toString()
            send(JSONObject().put("type", "response.cancel").put("event_id", eventId))
            cancellation.sent(eventId)
            responding = false
        }
        onAudioLevel(0f)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        check(!closed && handshake.ready)
        if (cameraCertain && enabled == cameraStarted && (enabled || !captureActive)) return
        if (!enabled) {
            cameraCertain = false
            videoSender?.let { check(it.setTrack(null, false)) }
            cameraFrames.stop()
            if (captureActive) {
                val stopped = CompletableDeferred<Unit>().also { videoStopped = it }
                try {
                    withContext(Dispatchers.IO) { capturer?.stopCapture() }
                    withTimeout(8000) { stopped.await() }
                } finally { videoStopped = null }
            }
            captureActive = false; cameraStarted = false; cameraCertain = true
            return
        }
        check(cameraAvailable) { "H264 camera encoding unavailable" }
        cameraCertain = false
        firstVideoFrame = CompletableDeferred()
        try {
            if (capturer == null) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                    ?: enumerator.deviceNames.first()
                cameraFront = enumerator.isFrontFacing(name)
                capturer = checkNotNull(enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(message: String) = dispatch { cameraFailed() }
                    override fun onCameraDisconnected() = dispatch { cameraFailed() }
                    override fun onCameraFreezed(message: String) = dispatch { fail("camera_frozen") }
                    override fun onCameraOpening(name: String) = Unit
                    override fun onFirstFrameAvailable() = dispatch { firstVideoFrame?.complete(Unit) }
                    override fun onCameraClosed() = dispatch { videoStopped?.complete(Unit) }
                }))
                videoSource = factory!!.createVideoSource(false)
                videoTrack = factory!!.createVideoTrack("omni-camera", videoSource)
                textureHelper = SurfaceTextureHelper.create("OmniCamera", eglContext)
                capturer!!.initialize(textureHelper, context, object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) {
                        videoSource?.capturerObserver?.onCapturerStarted(success)
                    }
                    override fun onCapturerStopped() {
                        cameraFrames.stop()
                        videoSource?.capturerObserver?.onCapturerStopped()
                    }
                    override fun onFrameCaptured(frame: VideoFrame) = cameraFrames.onFrame(frame)
                })
            }
            // Preview raw frames before the separately throttled model branch and native adaptation.
            captureActive = true
            cameraFrames.start()
            capturer!!.startCapture(1280, 720, AiCallVideoConfig.captureFps)
            withTimeout(8000) { firstVideoFrame!!.await() }
            check(!closed)
            check(videoSender!!.setTrack(videoTrack, false))
            val parameters = videoSender!!.parameters
            parameters.encodings.forEach { it.maxFramerate = AiCallVideoConfig.modelUploadFps; it.maxBitrateBps = 1_000_000 }
            videoSender!!.parameters = parameters
            cameraStarted = true; cameraCertain = true
        } finally { firstVideoFrame = null }
    }
    override fun publishCameraState() { answer?.let { tools?.publishState(it.instructions, cameraEnabled) } }

    private fun cameraFailed() {
        firstVideoFrame?.completeExceptionally(IllegalStateException("Camera capture failed"))
        videoStopped?.completeExceptionally(IllegalStateException("Camera stop failed"))
        if (cameraStarted) fail("camera_capture_failed")
    }

    override suspend fun flipCamera(): Boolean = suspendCancellableCoroutine { continuation ->
        val camera = capturer
        if (closed || !cameraStarted || camera == null) {
            continuation.resumeWithException(IllegalStateException("Camera is not active"))
            return@suspendCancellableCoroutine
        }
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = dispatch {
                cameraFront = front
                if (continuation.isActive) continuation.resume(front)
            }
            override fun onCameraSwitchError(message: String) = dispatch {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Camera switch failed"))
            }
        })
    }

    fun attachPreview(sink: VideoSink) {
        if (!closed) cameraFrames.attach(sink)
    }

    fun detachPreview(sink: VideoSink) {
        cameraFrames.detach(sink)
    }

    override suspend fun capturePhoto(): ByteArray {
        check(!closed && handshake.ready)
        return if (cameraStarted) photos.capture(cameraFront, cameraFrames::attach, cameraFrames::detach)
            else photos.capture(front = false)
    }

    private fun send(event: JSONObject) {
        if (!event.has("event_id")) event.put("event_id", UUID.randomUUID().toString())
        val channel = checkNotNull(eventChannel)
        check(channel.state() == DataChannel.State.OPEN)
        check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false)))
        if (event.optString("type") == "response.create") responseCreation.sent(event.getString("event_id"))
    }

    private fun startStats() {
        scope.launch {
            while (isActive && !closed) {
                peer?.getStats { report ->
                    val level = report.statsMap.values.firstOrNull {
                        it.type == "inbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
                    }?.members?.get("audioLevel") as? Number
                    val now = SystemClock.elapsedRealtime()
                    dispatch {
                        val amplitude = level?.toFloat() ?: 0f
                        if (!outputMuted && !outputSuppressed && !toolOutputHeld) playbackDiagnostics.sample(amplitude)
                        onAudioLevel(if (outputMuted || outputSuppressed || toolOutputHeld) 0f else amplitude)
                        if (amplitude > 0.01f && !outputMuted && !outputSuppressed && !toolOutputHeld) {
                            tools?.playback()
                            speechEndedAt?.let { Log.i("OmniWebRtc", "First playback after speech end: ${now - it} ms") }
                            speechEndedAt = null
                        }
                    }
                }
                delay(100)
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        scope.launch {
            if (!closed) runCatching(block).onFailure { fail("callback_${it.javaClass.simpleName}") }
        }
    }

    private fun fail(reason: String) {
        if (closed) return
        Log.w("OmniWebRtc", "Call failed reason=$reason camera=$cameraEnabled capturing=$captureActive responding=$responding")
        recovery.close()
        ready.completeExceptionally(IllegalStateException("AI connection failed"))
        iceComplete.completeExceptionally(IllegalStateException("AI connection failed"))
        onFailure()
    }

    /** Idempotent; callbacks queued by native WebRTC are ignored after closure. */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { photos.close() }
        firstVideoFrame?.cancel(); videoStopped?.cancel()
        recovery.close()
        handshake.close()
        responseCreation.close()
        ready.cancel()
        tools?.close()
        iceComplete.cancel()
        scope.cancel()
        channels.forEach {
            runCatching { it.unregisterObserver() }
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
        channels.clear()
        runCatching { peer?.close() }
        cameraFrames.close()
        runCatching { capturer?.stopCapture() }
        // A failed native cleanup must not prevent the remaining hardware and
        // routing resources from being released, including on partial startup.
        runCatching { capturer?.dispose() }
        runCatching { peer?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        runCatching { audioSwitch?.stop() }
        runCatching { egl?.release() }
        egl = null
    }

    private suspend fun createOffer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        pc.createOffer(object : SdpCallbacks() {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Cannot create call offer"))
            }
        }, MediaConstraints())
    }

    private suspend fun setDescription(pc: PeerConnection, sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
        val callback = object : SdpCallbacks() {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Cannot negotiate call media"))
            }
        }
        if (local) pc.setLocalDescription(callback, sdp) else pc.setRemoteDescription(callback, sdp)
    }

    private open class SdpCallbacks : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
