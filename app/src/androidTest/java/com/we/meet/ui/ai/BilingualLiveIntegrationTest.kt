package com.we.meet.ui.ai

import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.AssistantTranslationApi
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.capture.AndroidCapturePcmSource
import com.we.meet.data.capture.AndroidTranslationOutput
import com.we.meet.data.capture.OkHttpCaptureTranslationWire
import com.we.meet.feature.assistant.background.AssistantSessionLease
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Opt-in test: isolated real HTTPS backend + WSS gateway + paid provider. */
@RunWith(AndroidJUnit4::class)
class BilingualLiveIntegrationTest {
    @Test fun realBusinessAdmissionTranslationFinishAndReplay() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("bilingual_api")
        assumeTrue("Requires the isolated live integration harness", endpoint != null)
        require(endpoint!!.startsWith("https://localhost:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.decode(args.getString("bilingual_ca"), Base64.DEFAULT)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("isolated-harness", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val manager = trust.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val socketClient = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val apiClient = socketClient.newBuilder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
            val authorization = args.getString("bilingual_authorization")
            if (authorization != null) {
                request.header("Authorization", authorization)
            } else {
                request.header("Cookie", args.getString("bilingual_cookie")!!)
                    .header("X-CSRFToken", args.getString("bilingual_csrf")!!)
                    .header("Referer", endpoint)
            }
            chain.proceed(request.build())
        }.build()
        val api = Retrofit.Builder().baseUrl(endpoint).client(apiClient)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(AssistantTranslationApi::class.java)
        // Fail directly at the API boundary, rather than timing out on UI state.
        assertEquals(2, runBlocking { api.voiceConfig() }.protocolVersion)
        val physical = args.getString("bilingual_physical") == "true"
        val automatic = physical && args.getString("bilingual_physical_mode", "automatic") == "automatic"
        val backgroundNoise = args.getString("bilingual_physical_environment", "quiet") == "background_noise"
        val modeLabel = if (automatic) "自动方向" else "固定中文 → 英文"
        val environmentLabel = if (backgroundNoise) "非语音背景噪声" else "安静环境"
        val routeCatalog = mapOf("speaker" to ("扬声器" to 300L), "wired" to ("有线耳机" to 100L), "bluetooth" to ("蓝牙耳机" to 600L))
        val routes = if (physical) args.getString("bilingual_physical_routes", "speaker,wired,bluetooth")
            .split(',').map { requireNotNull(routeCatalog[it]) { "Unknown physical route" } }
            else listOf("模拟器" to 300L)
        require(routes.isNotEmpty() && routes.distinct().size == routes.size)
        val pcm = instrumentation.context.assets.open("aoq-chinese.pcm").use { it.readBytes() }
        var controller: BilingualTranslationController? = null
        val activity = ActivityScenario.launch(com.we.meet.MainActivity::class.java)
        if (physical) activity.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.setTurnScreenOn(true)
        }
        var statusView: TextView? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitCondition(timeout: Long = 60000, condition: () -> Boolean) = runBlocking {
            withTimeout(timeout) {
                while (!condition()) {
                    main { controller?.state?.value?.let { state ->
                        val text =
                        "状态：${state.phase}\n中文：${state.rows.count { it.sourceLanguage == "zh" }} 句，英文：${state.rows.count { it.sourceLanguage == "en" }} 句"
                        if (statusView?.text?.toString() != text) statusView?.text = text
                    } }
                    delay(25)
                }
            }
        }
        try {
          for ((route, expectedTail) in routes) {
            val source = SyntheticSource(pcm)
            val closed = AtomicBoolean()
            val tails = ConcurrentHashMap.newKeySet<Long>()
            main {
                controller = BilingualTranslationController(
                    instrumentation.targetContext, api, { true },
                    openMicrophone = { interrupted ->
                        val input = if (physical) AndroidCapturePcmSource.open(instrumentation.targetContext).also { it.onInterrupted = interrupted } else source
                        object : CapturePcmSource by input {
                            override fun close() { input.close(); closed.set(true) }
                        }
                    },
                    openOutput = { interrupted ->
                        val output = AndroidTranslationOutput(instrumentation.targetContext, interrupted, startupBufferMs = 80)
                        object : BilingualAudioOutput {
                            override fun open() = output.open()
                            override fun play(samples: ShortArray) { output.play(samples); tails.add(output.echoTailMs) }
                            override fun finishTurn() { output.finishTurn(); tails.add(output.echoTailMs) }
                            override fun mute(muted: Boolean) = output.mute(muted)
                            override val pendingSamples get() = output.pendingSamples
                            override val echoTailMs get() = output.echoTailMs
                            override fun close() = output.close()
                        }
                    },
                    openWire = { url, listener -> OkHttpCaptureTranslationWire.open(socketClient, url, listener) },
                    openForeground = { object : AssistantSessionLease {
                        override suspend fun camera(enabled: Boolean) = Unit
                        override fun close() = Unit
                    } },
                )
            }
            val active = controller!!
            awaitCondition { active.state.value.voiceConfig != null || active.state.value.unavailableReason != null }
            assertNull(active.state.value.unavailableReason)
            main { active.fixedSource(if (automatic) null else "zh"); active.voice("Ethan"); active.sound(physical) }
            if (physical) {
                activity.onActivity { screen ->
                    val column = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 48, 28, 28) }
                    column.addView(TextView(screen).apply { textSize = 24f; text = "双向翻译测试：$route\n$modeLabel · $environmentLabel" })
                    column.addView(TextView(screen).apply {
                        textSize = 19f
                        text = "连接好$route，按开始。\n\n" +
                            (if (backgroundNoise) "保持风扇等非语音背景声；不要播放含人声的音频。\n\n" else "在安静环境中测试。\n\n") +
                            "先说：你好，请问地铁站怎么走？\n\n" +
                            (if (automatic) "听完译音后说：Please tell me where the nearest station is.\n\n" else "") +
                            "随后停止说话十秒，保持当前环境声。程序会结束并回放最后一句，再进入下一路由。"
                    })
                    statusView = TextView(screen).apply { textSize = 18f }
                    column.addView(statusView)
                    column.addView(Button(screen).apply {
                        text = "开始$route 测试"
                        setOnClickListener {
                            if (screen.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                screen.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 712)
                                text = "授权后，点击开始$route 测试"
                            } else { isEnabled = false; text = "测试进行中，请按提示说话"; active.start() }
                        }
                    })
                    screen.setContentView(column)
                }
            } else main { active.start() }
            awaitCondition(if (physical) 300000 else 60000) { active.state.value.active || active.state.value.phase == BilingualPhase.ERROR }
            awaitCondition { active.state.value.phase != BilingualPhase.CONNECTING }
            assertTrue(active.state.value.active)
            val expectedRows = if (automatic) 2 else 1
            awaitCondition(if (physical) 120000 else 60000) {
                active.state.value.replayable.size >= expectedRows || !active.state.value.active
            }
            assertTrue(active.state.value.active)
            assertEquals(expectedRows, active.state.value.rows.size)
            val row = active.state.value.rows.last()
            assertEquals(if (automatic) setOf("zh", "en") else setOf("zh"), active.state.value.rows.map { it.sourceLanguage }.toSet())
            assertTrue(active.state.value.rows.all { it.source.isNotBlank() && it.text.isNotBlank() && it.id in active.state.value.replayable })
            if (physical) {
                awaitCondition { active.state.value.phase == BilingualPhase.LISTENING || !active.state.value.active }
                runBlocking { delay(10000) }
                assertEquals("Unexpected extra translations during quiet tail", expectedRows, active.state.value.rows.size)
            }
            main { active.finish() }
            awaitCondition { !active.state.value.active }
            assertEquals(BilingualPhase.IDLE, active.state.value.phase)
            assertFalse(active.state.value.finishIncomplete)
            assertTrue(closed.get())
            main { active.replay(row.id) }
            awaitCondition { !active.state.value.replaying }
            assertFalse(active.state.value.replayError)
            assertTrue(row.id in active.state.value.replayable)
            if (physical) assertTrue("Output route does not match $route", expectedTail in tails)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_result", "route=$route mode=${if (automatic) "automatic" else "fixed"} environment=${if (backgroundNoise) "background_noise" else "quiet"} rows=$expectedRows explicit_audio_end=true replay=true tails=${tails.sorted()}")
            })
            main { active.close(); controller = null }
          }
        } finally {
            main { controller?.close() }
            activity.close()
            apiClient.connectionPool.evictAll()
            socketClient.connectionPool.evictAll()
            socketClient.dispatcher.executorService.shutdown()
        }
    }

    private class SyntheticSource(pcm: ByteArray) : CapturePcmSource {
        private val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val closed = AtomicBoolean()
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(buffer.size * 1000L / 16000)
            if (closed.get()) return 0
            buffer.fill(0)
            val count = minOf(buffer.size, samples.remaining())
            samples.get(buffer, 0, count)
            return buffer.size
        }
        override fun stop() { closed.set(true) }
        override fun close() { stop() }
    }
}
