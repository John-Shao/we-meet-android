package com.we.meet.ui.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in live capability probe. Credentials are supplied privately, never committed. */
class AoqTranslationCapabilityTest {
    @Test fun translation38AcceptsNativeAoqSessionConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val credentialsFile = File(context.filesDir, "aoq-translation-probe.json")
        assumeTrue("Live probe requires temporary allocation", credentialsFile.exists())
        val credentials = JSONObject(credentialsFile.readText())
        val ready = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        var engine: AoqClientEngine? = null
        val session = JSONObject()
            .put("output_modalities", org.json.JSONArray(listOf("text", "audio")))
            .put("audio", JSONObject()
                .put("input", JSONObject()
                    .put("format", JSONObject().put("type", "pcm").put("sample_rate", 16000))
                    .put("turn_detection", JSONObject().put("type", "server_vad")
                        .put("threshold", 0.2).put("silence_duration_ms", 1000)))
                .put("output", JSONObject()
                    .put("format", JSONObject().put("type", "pcm").put("sample_rate", 24000))
                    .put("voice", "Tina")))
            .put("translation", JSONObject().put("language", "en"))
        try {
            instrumentation.runOnMainSync {
                engine = AoqClientEngine.createEngine(context, AoqCreateConfig().apply {
                    workDir = context.filesDir.absolutePath
                }, object : AoqClientListener() {
                    override fun onError(code: Int, message: String?) {
                        error.set("sdk_$code"); ready.countDown()
                    }
                    override fun onConnectionStatusChange(status: AoqConnectionStatus) {
                        if (status == AoqConnectionStatus.AoqConnectionStatusConnected) {
                            val event = JSONObject().put("type", "session.update").put("session", session)
                            val result = engine!!.sendDataMsg(AoqDataMsg().apply {
                                data = event.toString().toByteArray(Charsets.UTF_8)
                            })
                            if (result != 0) { error.set("send_$result"); ready.countDown() }
                        } else if (status == AoqConnectionStatus.AoqConnectionStatusFailed) {
                            error.set("connection_failed"); ready.countDown()
                        }
                    }
                    override fun onDataMsg(msg: AoqDataMsg) {
                        val event = JSONObject(String(msg.data, Charsets.UTF_8))
                        when (event.optString("type")) {
                            "session.updated" -> ready.countDown()
                            "error" -> {
                                error.set(event.optJSONObject("error")?.optString("code") ?: "session_error")
                                ready.countDown()
                            }
                        }
                    }
                })
                val sdk = engine!!
                assertEquals(0, sdk.enableSendMediaStream(AoqTrackType.AoqTrackTypeAudio, false))
                assertEquals(0, sdk.setAudioEncoderConfig(AoqAudioCodecConfig().apply {
                    codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 16000; channel = 1
                }))
                assertEquals(0, sdk.setAudioDecoderConfig(AoqAudioCodecConfig().apply {
                    codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 24000; channel = 1
                }))
                assertEquals(0, sdk.connect(AoqConnectConfig().apply {
                    token = credentials.getString("aoqTokenForClient")
                    sid = credentials.getString("sid")
                    certFingerprint = credentials.getString("clientRelayCertFingerprint")
                    workspaceIdHash = credentials.getJSONObject("extraInfo").getString("workspaceIdHash")
                    val relays = credentials.getJSONArray("clientRelayEndpoints")
                    for (i in 0 until relays.length()) {
                        val relay = relays.getJSONObject(i)
                        relayEndpoints.add(AoqRelayEndpoint().apply {
                            endpoint = relay.getString("endpoint"); port = relay.getInt("port")
                            routeIndex = relay.optInt("route_index", i)
                        })
                    }
                    for (type in listOf(AoqTrackType.AoqTrackTypeAudio, AoqTrackType.AoqTrackTypeData)) {
                        publishTracks.add(AoqTrackParam().apply { trackType = type })
                        subscribeTracks.add(AoqTrackParam().apply { trackType = type })
                    }
                }))
            }
            assertTrue("AOQ session configuration timed out", ready.await(25, TimeUnit.SECONDS))
            assertEquals("AOQ translation setup error", null, error.get())
        } finally {
            credentialsFile.delete()
            instrumentation.runOnMainSync {
                engine?.sendDataMsg(AoqDataMsg().apply {
                    data = "{\"type\":\"session.finish\"}".toByteArray(Charsets.UTF_8)
                })
                engine?.disconnect()
                AoqClientEngine.destroy()
            }
        }
    }
}
