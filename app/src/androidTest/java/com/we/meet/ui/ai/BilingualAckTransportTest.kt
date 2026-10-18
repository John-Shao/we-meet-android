package com.we.meet.ui.ai

import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.*
import com.we.meet.data.capture.*
import com.we.meet.feature.assistant.background.AssistantSessionLease
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real TLS WebSockets; fixture admission, synthetic capture, actual AudioTrack. */
@RunWith(AndroidJUnit4::class)
class BilingualAckTransportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(timeout: Long = 10000, condition: () -> Boolean) = runBlocking {
        withTimeout(timeout) { while (!condition()) delay(10) }
    }

    @Test fun busyMainDuringLongReplyDoesNotStarveHealthySocketAcks() {
        runCase(dropAcks = false)
    }

    @Test fun realSocketWithoutAcksStillFailsWithinTheExistingBudget() {
        runCase(dropAcks = true)
    }

    private fun runCase(dropAcks: Boolean) {
        val encoded = InstrumentationRegistry.getArguments().getString("bilingual_ack_tls")
        assumeTrue("Requires an ephemeral fixture-only PKCS12", encoded != null)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT)), "fixture-only".toCharArray())
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-only".toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val manager = trust.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(tls.socketFactory, manager).build()
        val frames = AtomicInteger()
        val networkAcks = AtomicInteger()
        val played = AtomicLong()
        val secondInput = CountDownLatch(1)
        val mainBusy = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val server = MockWebServer().apply { useHttps(tls.socketFactory, false) }
        fun audio() = JSONObject().put("type", "audio").put("id", "long")
            .put("audio", Base64.encodeToString(ByteArray(24000), Base64.NO_WRAP)).toString()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (JSONObject(text).getString("type") == "assistant_translation") {
                    socket.send("{\"type\":\"ready\",\"protocol_version\":2,\"input_sample_rate\":16000,\"output_sample_rate\":24000}")
                }
            }
            override fun onMessage(socket: WebSocket, bytes: ByteString) {
                val number = frames.incrementAndGet()
                if (dropAcks) return
                if (number == 2) {
                    secondInput.countDown()
                    check(mainBusy.await(10, TimeUnit.SECONDS))
                    // ACK reaches the OkHttp receive thread while Main is busy.
                    Thread.sleep(800)
                }
                socket.send("{\"type\":\"ack\"}")
                if (number == 1) socket.send(audio())
                if (number == 2) {
                    repeat(23) { socket.send(audio()) }
                    socket.send("{\"type\":\"translation\",\"id\":\"long\",\"source_language\":\"zh\",\"target_language\":\"en\",\"source\":\"fixture\",\"text\":\"fixture\"}")
                    socket.send("{\"type\":\"audio_end\",\"id\":\"long\"}")
                }
            }
        }))
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        // Do not depend on the phone's external DNS for a loopback-only regression.
        val ticketUrl = "wss://127.0.0.1:${server.port}/capture-translation"
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("ack_stage", "loopback_ready drop_acks=$dropAcks") })
        var controller: BilingualTranslationController? = null
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            try {
                main {
                    controller = BilingualTranslationController(instrumentation.targetContext,
                        object : AssistantTranslationApi {
                            override suspend fun voiceConfig() = TranslationVoiceConfig("fixture", "Tina", listOf(TranslationVoice("Tina", "Tina")), 2)
                            override suspend fun ticket(request: AssistantTranslationRequest) = AssistantTranslationTicket(ticketUrl, "fixture-only", 2)
                        }, { true },
                        openMicrophone = { object : CapturePcmSource {
                            override fun start() = Unit
                            override fun read(buffer: ShortArray): Int {
                                // Leave room in the old five-second FIFO to isolate the ACK failure.
                                Thread.sleep(150)
                                if (stopped.get()) return 0
                                buffer.fill(0); return buffer.size
                            }
                            override fun stop() { stopped.set(true) }
                            override fun close() = stop()
                        } },
                        openOutput = { interrupted ->
                            val output = AndroidTranslationOutput(instrumentation.targetContext, interrupted, startupBufferMs = 80)
                            object : BilingualAudioOutput {
                                override fun open() = output.open()
                                override fun play(samples: ShortArray) { output.play(samples); played.addAndGet(samples.size.toLong()) }
                                override fun finishTurn() = output.finishTurn()
                                override fun mute(muted: Boolean) = output.mute(muted)
                                override val pendingSamples get() = output.pendingSamples
                                override val echoTailMs get() = output.echoTailMs
                                override fun close() = output.close()
                            }
                        },
                        openWire = { url, listener -> OkHttpCaptureTranslationWire.open(client, url,
                            object : CaptureTranslationWire.Listener by listener {
                                override fun message(text: String) {
                                    if (JSONObject(text).optString("type") == "ack") networkAcks.incrementAndGet()
                                    listener.message(text)
                                }
                            }) },
                        openForeground = { object : AssistantSessionLease {
                            override suspend fun camera(enabled: Boolean) = Unit
                            override fun close() = Unit
                        } })
                    controller!!.start()
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("ack_stage", "controller_started drop_acks=$dropAcks") })
                val active = controller!!
                if (dropAcks) {
                    await { frames.get() > 0 }
                    val start = SystemClock.elapsedRealtime()
                    await(7500) { !active.state.value.active }
                    assertEquals(BilingualFailure.ACK_TIMEOUT, active.state.value.failure)
                    assertTrue(SystemClock.elapsedRealtime() - start < 6500)
                } else {
                    await { played.get() > 0 }
                    assertTrue(secondInput.await(5, TimeUnit.SECONDS))
                    val before = frames.get()
                    val duringMainBusy = AtomicInteger()
                    main {
                        mainBusy.countDown()
                        Thread.sleep(6200)
                        duringMainBusy.set(frames.get())
                    }
                    Thread.sleep(300)
                    // Give an already-due watchdog a chance to run before assessing state.
                    main {}
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("ack_busy_observation", "frames_before=$before frames_during_busy=${duringMainBusy.get()} network_acks=${networkAcks.get()} reason=${active.state.value.failure?.name}")
                    })
                    assertTrue("Acks reached the network thread", networkAcks.get() >= 2)
                    assertNull("Healthy network ACKs must not time out behind Main: ${active.state.value.failure}", active.state.value.failure)
                    assertTrue("Input must continue independently of Main", duringMainBusy.get() >= before + 20)
                    await(25000) { !active.state.value.active || played.get() == 288000L && active.state.value.phase == BilingualPhase.LISTENING }
                    assertTrue(active.state.value.active)
                    assertNull(active.state.value.failure)
                    assertEquals(288000L, played.get())
                    assertEquals(1, active.state.value.rows.size)
                    assertEquals(1, active.state.value.replayable.size)
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("bilingual_ack_transport", "drop_acks=$dropAcks frames=${frames.get()} network_acks=${networkAcks.get()} played_samples=${played.get()} reason=${active.state.value.failure?.name}")
                })
            } finally {
                main { controller?.close() }
                server.shutdown()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }
}
