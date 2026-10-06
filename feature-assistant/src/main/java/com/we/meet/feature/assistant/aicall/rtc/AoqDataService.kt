package com.we.meet.feature.assistant.aicall.rtc

import android.app.Service
import android.content.Intent
import android.os.*
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import org.json.JSONObject

/** One native engine per dedicated process. No microphone, player, or account store. */
open class AoqDataService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var peer: Messenger? = null
    private var engine: AoqClientEngine? = null
    private var generation = 0
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            runCatching {
                when (msg.what) {
                    1 -> { check(engine == null); peer = msg.replyTo; connect(JSONObject(msg.data.getString("allocation")!!)) }
                    2 -> {
                        val text = msg.data.getString("text")!!
                        check(text.length <= 100_000)
                        check(engine!!.sendDataMsg(AoqDataMsg().apply { data = text.toByteArray(Charsets.UTF_8) }) == 0)
                        reply(4, msg.arg1.toString())
                    }
                    3 -> release()
                }
            }.onFailure { android.util.Log.w("AoqDataService", "operation_failed class=${it.javaClass.simpleName}"); reply(3, "failed"); release() }
        }
    })
    override fun onBind(intent: Intent) = incoming.binder
    override fun onUnbind(intent: Intent): Boolean { release(); return false }
    override fun onDestroy() { release(); super.onDestroy() }

    private fun connect(credentials: JSONObject) {
        val current = ++generation
        engine = checkNotNull(AoqClientEngine.createEngine(this, AoqCreateConfig().apply {
            workDir = filesDir.absolutePath
        }, object : AoqClientListener() {
            override fun onError(code: Int, message: String?) { main.post { if (current == generation) { android.util.Log.w("AoqDataService", "sdk_error=$code"); reply(3, "sdk_$code") } } }
            override fun onConnectionStatusChange(status: AoqConnectionStatus) {
                main.post {
                    if (current != generation) return@post
                    android.util.Log.i("AoqDataService", "connection=$status")
                    when (status) {
                        AoqConnectionStatus.AoqConnectionStatusConnected -> {
                            if (this@AoqDataService !is AoqLanguageService) {
                                val result = engine!!.startAudioPlayer(AoqAudioPlaybackConfig().apply {
                                    channel = 1; isExternal = true; isVoipMode = false
                                })
                                if (result != 0) { reply(3, "player_$result"); return@post }
                            }
                            reply(1, "connected")
                        }
                        AoqConnectionStatus.AoqConnectionStatusFailed -> reply(3, "connection_failed")
                        AoqConnectionStatus.AoqConnectionStatusDisconnected -> reply(3, "disconnected")
                        else -> Unit
                    }
                }
            }
            override fun onDataMsg(msg: AoqDataMsg) {
                val bytes = msg.data
                if (bytes.size > 300_000) { main.post { reply(3, "event_size") }; return }
                val text = String(bytes, Charsets.UTF_8)
                main.post { if (current == generation) reply(2, text) }
            }
        }))
        val sdk = engine!!
        check(sdk.enableSendMediaStream(AoqTrackType.AoqTrackTypeAudio, false) == 0)
        check(sdk.setAudioEncoderConfig(AoqAudioCodecConfig().apply {
            codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 16000; channel = 1
        }) == 0)
        check(sdk.setAudioDecoderConfig(AoqAudioCodecConfig().apply {
            codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 24000; channel = 1
        }) == 0)
        if (this !is AoqLanguageService) {
            check(sdk.setAudioFrameObserver(object : AoqClientListener.AoqAudioFrameListener {
                override fun onPlaybackAudioFrame(frame: AoqAudioFrameData) {
                    if (frame.autoGenMute || frame.bytesPerSample != 2 || frame.dataSize <= 0) return
                    val data = frame.dataPtr ?: return
                    val bytes = data.copyOf(minOf(frame.dataSize, data.size))
                    val event = JSONObject().put("type", "aoq.audio")
                        .put("audio", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).toString()
                    main.post { if (current == generation) reply(2, event) }
                }
            }) == 0)
            check(sdk.enableAudioFrameObserver(true, AoqAudioSource.AoqAudioSourcePlayback, AoqAudioObserverConfig().apply {
                sampleRate = 24000; channels = 1; mode = AoqAudioObserverMode.AoqAudioObserverModeReadOnly
            }) == 0)
        }
        check(sdk.connect(AoqConnectConfig().apply {
            token = credentials.getString("aoqTokenForClient"); sid = credentials.getString("sid")
            certFingerprint = credentials.getString("clientRelayCertFingerprint")
            workspaceIdHash = credentials.getString("workspaceIdHash")
            val relays = credentials.getJSONArray("clientRelayEndpoints")
            for (i in 0 until relays.length()) {
                val relay = relays.getJSONObject(i)
                relayEndpoints.add(AoqRelayEndpoint().apply {
                    endpoint = relay.getString("endpoint"); port = relay.getInt("port")
                    routeIndex = relay.optInt("route_index", i)
                })
            }
            // Input PCM is carried in Realtime JSON. Output Opus is decoded by
            // the external player and forwarded to the app's playback/replay queue.
            for (type in listOf(AoqTrackType.AoqTrackTypeAudio, AoqTrackType.AoqTrackTypeData)) {
                publishTracks.add(AoqTrackParam().apply { trackType = type })
                subscribeTracks.add(AoqTrackParam().apply { trackType = type })
            }
        }) == 0)
    }
    private fun reply(kind: Int, text: String) {
        runCatching { peer?.send(Message.obtain(null, kind).apply { data = Bundle().apply { putString("text", text) } }) }
            .onFailure { release() }
    }
    private fun release() {
        generation++
        runCatching { engine?.disconnect() }
        runCatching { engine?.stopAudioPlayer() }
        if (engine != null) runCatching { AoqClientEngine.destroy() }
        engine = null; peer = null
    }
}

class AoqLanguageService : AoqDataService()
class AoqReverseTranslationService : AoqDataService()
