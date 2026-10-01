package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
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
) {
    private val transcript = OmniTranscript(onTranscript)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handshake = OmniHandshake()
    private val recovery = OmniConnectionRecovery(scope, ::fail)
    private val cancellation = OmniCancellation(SystemClock::elapsedRealtime)
    private val iceComplete = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val channels = mutableSetOf<DataChannel>()
    private val sinks = mutableSetOf<VideoSink>()
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
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var cameraStarted = false
    var cameraFront = false
        private set
    private var answer: AiCallAnswer? = null
    private var responding = false
    private var closed = false

    suspend fun connect(exchange: suspend (String) -> AiCallAnswer) {
        withTimeout(60_000) {
            initialize()
            val pc = checkNotNull(peer)
            val offer = createOffer(pc)
            setDescription(pc, offer, local = true)
            withTimeout(15_000) { iceComplete.await() }
            answer = exchange(checkNotNull(pc.localDescription).description)
            check(!closed)
            setDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, answer!!.sdp), local = false)
            withTimeout(25_000) { ready.await() }
            check(!closed)
            // Do not capture or send microphone data before session.updated.
            audioSource = factory!!.createAudioSource(MediaConstraints())
            audioTrack = factory!!.createAudioTrack("omni-microphone", audioSource)
            check(audioSender!!.setTrack(audioTrack, false))
            startStats()
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
                if (focus == AudioManager.AUDIOFOCUS_LOSS) dispatch { fail() }
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
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
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
        videoSender = peer!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        ).sender
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
                PeerConnection.PeerConnectionState.CLOSED -> fail()
                else -> handshake.connected = false
            }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceComplete.complete(Unit)
        }
        override fun onDataChannel(channel: DataChannel) = dispatch { bindChannel(channel) }
        override fun onTrack(transceiver: RtpTransceiver) = dispatch {
            remoteAudio = transceiver.receiver.track() as? AudioTrack
            remoteAudio?.setEnabled(true)
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
                    if (channel.state() == DataChannel.State.CLOSED) fail()
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
        transcript.accept(event)
        when (event.optString("type")) {
            "session.created" -> {
                // Server-created 'txt' is supported as well as the local channel.
                eventChannel = channel
                handshake.sessionCreated = true
                handshake.channelOpen = channel.state() == DataChannel.State.OPEN
                configureIfReady()
            }
            "session.updated" -> if (handshake.acknowledge()) ready.complete(Unit)
            "response.created" -> {
                responding = true
                remoteAudio?.setEnabled(true)
            }
            "response.done" -> responding = false
            "input_audio_buffer.speech_started" -> remoteAudio?.setEnabled(false)
            "error" -> {
                val error = event.optJSONObject("error")
                if (error == null || !handshake.ready || !cancellation.recoverable(
                        error.optString("type"), error.optString("code"),
                        error.optString("message"), error.optString("event_id"), error.optString("param"),
                    )) fail()
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
            .put("instructions", config.instructions)
            .put("input_audio_transcription", JSONObject().put("model", "qwen3-asr-flash-realtime"))
            .put("turn_detection", JSONObject().put("type", "server_vad")
                .put("threshold", 0.5).put("silence_duration_ms", 800))))
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        check(!closed && audioTrack?.setEnabled(enabled) == true)
    }

    fun interrupt() {
        check(!closed && handshake.ready)
        remoteAudio?.setEnabled(false)
        if (responding) {
            val eventId = UUID.randomUUID().toString()
            send(JSONObject().put("type", "response.cancel").put("event_id", eventId))
            cancellation.sent(eventId)
            responding = false
        }
        onAudioLevel(0f)
    }

    fun setCameraEnabled(enabled: Boolean) {
        check(!closed && handshake.ready)
        if (enabled == cameraStarted) return
        if (!enabled) {
            check(videoSender!!.setTrack(null, false))
            capturer?.stopCapture()
            cameraStarted = false
            return
        }
        try {
            if (capturer == null) {
                val enumerator = Camera2Enumerator(context)
                val name = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                    ?: enumerator.deviceNames.first()
                cameraFront = enumerator.isFrontFacing(name)
                capturer = checkNotNull(enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(message: String) = dispatch { fail() }
                    override fun onCameraDisconnected() = dispatch { fail() }
                    override fun onCameraFreezed(message: String) = dispatch { fail() }
                    override fun onCameraOpening(name: String) = Unit
                    override fun onFirstFrameAvailable() = Unit
                    override fun onCameraClosed() = Unit
                }))
                videoSource = factory!!.createVideoSource(false)
                videoTrack = factory!!.createVideoTrack("omni-camera", videoSource)
                textureHelper = SurfaceTextureHelper.create("OmniCamera", eglContext)
                capturer!!.initialize(textureHelper, context, videoSource!!.capturerObserver)
            }
            // Native video RTP. Keep visual input low-rate for scene Q&A.
            capturer!!.startCapture(1280, 720, 2)
            cameraStarted = true
            check(videoSender!!.setTrack(videoTrack, false))
            val parameters = videoSender!!.parameters
            parameters.encodings.forEach { it.maxFramerate = 2; it.maxBitrateBps = 1_000_000 }
            videoSender!!.parameters = parameters
        } catch (error: Exception) {
            videoSender?.setTrack(null, false)
            runCatching { capturer?.stopCapture() }
            cameraStarted = false
            throw error
        }
    }

    suspend fun flipCamera(): Boolean = suspendCancellableCoroutine { continuation ->
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
        if (!closed && sinks.add(sink)) videoTrack?.addSink(sink)
    }

    fun detachPreview(sink: VideoSink) {
        if (!closed && sinks.remove(sink)) videoTrack?.removeSink(sink)
    }

    private fun send(event: JSONObject) {
        if (!event.has("event_id")) event.put("event_id", UUID.randomUUID().toString())
        val channel = checkNotNull(eventChannel)
        check(channel.state() == DataChannel.State.OPEN)
        check(channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false)))
    }

    private fun startStats() {
        scope.launch {
            while (isActive && !closed) {
                peer?.getStats { report ->
                    val level = report.statsMap.values.firstOrNull {
                        it.type == "inbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio")
                    }?.members?.get("audioLevel") as? Number
                    dispatch { onAudioLevel(level?.toFloat() ?: 0f) }
                }
                delay(100)
            }
        }
    }

    private fun dispatch(block: () -> Unit) {
        scope.launch {
            if (!closed) runCatching(block).onFailure { fail() }
        }
    }

    private fun fail() {
        if (closed) return
        recovery.close()
        ready.completeExceptionally(IllegalStateException("AI connection failed"))
        iceComplete.completeExceptionally(IllegalStateException("AI connection failed"))
        onFailure()
    }

    /** Idempotent; callbacks queued by native WebRTC are ignored after closure. */
    fun close() {
        if (closed) return
        closed = true
        recovery.close()
        handshake.close()
        ready.cancel()
        iceComplete.cancel()
        scope.cancel()
        channels.forEach {
            runCatching { it.unregisterObserver() }
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
        channels.clear()
        runCatching { peer?.close() }
        sinks.forEach { runCatching { videoTrack?.removeSink(it) } }
        sinks.clear()
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
