package com.we.meet.ui.ai

import android.content.Context
import android.media.AudioFormat
import android.util.Base64
import com.we.meet.data.api.AssistantTranslationDirectSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.*
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio uses RTP. The only microphone/player belong to the translation controller. */
internal class WebRtcBilingualConnection(private val context: Context, private val detection: Boolean) : BilingualModelConnection {
    override val events = Channel<JSONObject>(256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ice = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val pcm = BilingualPcmBuffer()
    private val channels = mutableSetOf<DataChannel>()
    private var channel: DataChannel? = null
    private var connected = false
    private var created = false
    private var module: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var sender: RtpSender? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var remote: AudioTrack? = null
    @Volatile private var closed = false
    private var failed = false
    private var mediaStarted = false
    private var lastPcmMark = 0L
    private val inputClock = BilingualAudioClock()

    override suspend fun connect(allocate: suspend (String?) -> AssistantTranslationDirectSession) = withContext(Dispatchers.Main.immediate) {
        withTimeout(60_000) {
            check(!closed && peer == null)
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            module = JavaAudioDeviceModule.builder(context)
                .setInputSampleRate(16000).setUseStereoInput(false)
                .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                .setAudioBufferCallback { buffer, format, channels, rate, _, _ ->
                    // External mode has no blocking AudioRecord.read(). Its callback
                    // must provide the media clock, or the SDK spins and floods RTP.
                    val duration = if (channels > 0 && rate > 0) buffer.capacity().toLong() * 1_000_000_000 / (2 * channels * rate) else 10_000_000L
                    val timestamp = inputClock.frame(duration.coerceAtLeast(1))
                    if (format != AudioFormat.ENCODING_PCM_16BIT || channels != 1 || rate != 16000) {
                        dispatch { fail("capture_format") }
                        buffer.clear(); while (buffer.hasRemaining()) buffer.put(0.toByte()); buffer.rewind()
                    } else pcm.fill(buffer)
                    timestamp
                }.createAudioDeviceModule().also {
                    // Supported external capture mode: no extra AudioRecord is opened.
                    it.setAudioRecordEnabled(false)
                    it.setSpeakerMute(true)
                }
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            peer = checkNotNull(factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, observer))
            sender = peer!!.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)).sender
            setSending(false)
            bind(peer!!.createDataChannel("oai-events", DataChannel.Init()))
            val offer = createOffer(peer!!)
            describe(peer!!, offer, true)
            withTimeout(15_000) { ice.await() }
            val allocation = allocate(checkNotNull(peer!!.localDescription).description)
            check(!closed)
            val answer = checkNotNull(allocation.sdp).trim().replace(Regex("\r?\n"), "\r\n") + "\r\n"
            describe(peer!!, SessionDescription(SessionDescription.Type.ANSWER, answer), false)
            withTimeout(25_000) { ready.await() }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = dispatch {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connected = true; checkReady() }
                PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> fail("peer_${state.name.lowercase()}")
                else -> Unit
            }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) ice.complete(Unit)
        }
        override fun onDataChannel(channel: DataChannel) = dispatch { bind(channel) }
        override fun onTrack(transceiver: RtpTransceiver) = dispatch {
            (transceiver.receiver.track() as? AudioTrack)?.let {
                remote = it
                if (!detection) it.addSink(audioSink)
            }
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
    private val audioSink = AudioTrackSink { data, bits, rate, channels, frames, _ ->
        if (!closed) {
            runCatching {
                check(bits == 16 && channels in 1..2 && rate in setOf(24000, 48000))
                val samples = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val stride = rate / 24000
                val output = ByteBuffer.allocate((frames / stride) * 2).order(ByteOrder.LITTLE_ENDIAN)
                var nonSilent = false
                for (i in 0 until frames / stride) {
                    val sample = samples.get(i * stride * channels)
                    if (kotlin.math.abs(sample.toInt()) > 16) nonSilent = true
                    output.putShort(sample)
                }
                offerEvent(JSONObject().put("type", "rtc.audio").put("silent", !nonSilent)
                    .put("audio", Base64.encodeToString(output.array(), Base64.NO_WRAP)))
            }.onFailure { dispatch { fail() } }
        }
    }
    private fun bind(value: DataChannel) {
        if (!channels.add(value)) return
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = dispatch {
                if (value === channel && value.state() == DataChannel.State.CLOSED) fail()
                checkReady()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                if (buffer.data.remaining() > 300000) { dispatch { fail() }; return }
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                dispatch {
                    val event = JSONObject(String(bytes, Charsets.UTF_8))
                    if (event.optString("type") in setOf("session.created", "session.updated", "session.finished", "response.created", "response.done"))
                        android.util.Log.i("WebRtcBilingual", "model_event=${event.optString("type")}")
                    if (event.optString("type") == "error") {
                        val detail = event.optJSONObject("error")
                        val message = detail?.optString("message").orEmpty()
                        val fields = listOf("sample_rate", "output_modalities", "modalities", "turn_detection", "format", "voice", "translation", "audio", "threshold").filter { message.contains(it) }
                        android.util.Log.w("WebRtcBilingual", "model_error_code=${detail?.optString("code")} fields=$fields")
                    }
                    when (event.optString("type")) {
                        "session.created" -> { created = true; channel = value; checkReady() }
                        "session.updated" -> startMedia()
                    }
                    offerEvent(event)
                }
            }
        })
    }
    private fun checkReady() {
        if (connected && created && channel?.state() == DataChannel.State.OPEN) ready.complete(Unit)
    }
    private fun startMedia() {
        if (mediaStarted) return
        source = factory!!.createAudioSource(MediaConstraints())
        track = factory!!.createAudioTrack("bilingual-external-pcm", source)
        check(sender!!.setTrack(track, false))
        setSending(true)
        mediaStarted = true
    }
    private fun setSending(enabled: Boolean) {
        val value = checkNotNull(sender)
        val parameters = value.parameters
        parameters.encodings.forEach { it.active = enabled }
        check(value.setParameters(parameters))
    }
    override suspend fun send(event: JSONObject) {
        check(!closed)
        when (event.optString("type")) {
            "input_audio_buffer.append" -> {
                check(mediaStarted)
                // Keep capture and RTP clocks independent; awaiting every packet
                // would accumulate scheduling gaps in continuous speech.
                lastPcmMark = pcm.append(Base64.decode(event.getString("audio"), Base64.NO_WRAP))
                return
            }
            "input_audio_buffer.clear" -> pcm.clear()
            "input_audio_buffer.commit", "session.finish" -> {
                withTimeout(12_000) { while (!pcm.drained(lastPcmMark)) { check(!closed); delay(5) } }
                delay(150) // RTP must reach the provider before the data-channel boundary.
            }
        }
        if (!event.has("event_id")) event.put("event_id", UUID.randomUUID().toString())
        withContext(Dispatchers.Main.immediate) {
            val value = checkNotNull(channel)
            check(value.state() == DataChannel.State.OPEN && value.bufferedAmount() <= 64000)
            check(value.send(DataChannel.Buffer(ByteBuffer.wrap(event.toString().toByteArray(Charsets.UTF_8)), false)))
        }
    }
    private fun offerEvent(event: JSONObject) {
        if (!closed && !events.trySend(event).isSuccess) dispatch { fail() }
    }
    private fun dispatch(action: () -> Unit) { scope.launch { if (!closed) runCatching(action).onFailure {
        val frame = it.stackTrace.firstOrNull()
        android.util.Log.w("WebRtcBilingual", "callback_failure=${it.javaClass.simpleName} at=${frame?.className}:${frame?.lineNumber}")
        fail("callback")
    } } }
    private fun fail(reason: String = "transport") {
        if (closed || failed) return
        failed = true
        android.util.Log.w("WebRtcBilingual", "failure_reason=$reason")
        val error = IllegalStateException("WebRTC translation connection failed")
        ice.completeExceptionally(error); ready.completeExceptionally(error); events.close(error)
    }
    override fun close() {
        if (closed) return
        closed = true; scope.cancel(); pcm.close(); ice.cancel(); ready.cancel(); events.cancel()
        runCatching { remote?.removeSink(audioSink) }
        channels.forEach { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }; channels.clear()
        runCatching { peer?.close() }; runCatching { peer?.dispose() }
        runCatching { track?.dispose() }; runCatching { source?.dispose() }
        runCatching { factory?.dispose() }; runCatching { module?.release() }
    }
    private suspend fun createOffer(pc: PeerConnection): SessionDescription = suspendCancellableCoroutine { cont ->
        pc.createOffer(object : SdpCallbacks() {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException("Cannot create translation offer")) }
        }, MediaConstraints())
    }
    private suspend fun describe(pc: PeerConnection, sdp: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { cont ->
        val callback = object : SdpCallbacks() {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String) {
                // Native SDP validation messages describe codecs/directions, never log SDP itself.
                val safe = if (error.contains("SessionDescription is NULL")) "invalid_description" else "negotiation_rejected"
                android.util.Log.w("WebRtcBilingual", "sdp_failure local=$local reason=$safe")
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Cannot negotiate translation media"))
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
