package com.we.meet.ui.ai

import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Opt-in isolated or authenticated production acceptance; never enabled by default. */
@RunWith(AndroidJUnit4::class)
class BilingualLiveIntegrationTest {
    @Test fun realBusinessAdmissionTranslationFinishAndReplay() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("bilingual_api")
        val production = args.getString("bilingual_production") == "true"
        assumeTrue("Requires an explicit isolated or production acceptance run", production || endpoint != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as com.we.meet.WeMeetApp
        val account = if (production) app.captureAccount else null
        var socketClient: OkHttpClient? = null
        var apiClient: OkHttpClient? = null
        val api: AssistantTranslationApi
        if (production) {
            // Production always uses the app's existing authenticated API and normal TLS.
            require(endpoint == null && args.getString("bilingual_ca") == null)
            require(args.getString("bilingual_authorization") == null && args.getString("bilingual_cookie") == null)
            require(com.we.meet.BuildConfig.WE_MEET_BASE_URL.startsWith("https://"))
            check(account != null) { "Log in to the app before production acceptance" }
            api = app.apiClient.assistantTranslationApi
        } else {
            require(endpoint!!.startsWith("https://localhost:"))
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(Base64.decode(args.getString("bilingual_ca"), Base64.DEFAULT)))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setCertificateEntry("isolated-harness", certificate)
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val manager = trust.trustManagers.single() as X509TrustManager
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            socketClient = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            apiClient = socketClient!!.newBuilder().addInterceptor { chain ->
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
            api = Retrofit.Builder().baseUrl(endpoint).client(apiClient)
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
                .build().create(AssistantTranslationApi::class.java)
        }
        // Fail directly at the API boundary, rather than timing out on UI state.
        assertEquals(2, runBlocking { api.voiceConfig() }.protocolVersion)
        val physical = args.getString("bilingual_physical") == "true"
        val syntheticPair = args.getString("bilingual_synthetic_pair") == "true"
        val audibleSynthetic = args.getString("bilingual_audible_synthetic") == "true"
        require(!syntheticPair || !physical)
        require(!audibleSynthetic || syntheticPair && !physical)
        val automatic = (physical || syntheticPair) && args.getString("bilingual_physical_mode", "automatic") == "automatic"
        val backgroundNoise = args.getString("bilingual_physical_environment", "quiet") == "background_noise"
        val modeLabel = if (automatic) "自动方向" else "固定中文 → 英文"
        val environmentLabel = if (backgroundNoise) "非语音背景噪声" else "安静环境"
        val routeCatalog = mapOf("speaker" to ("扬声器" to 300L), "wired" to ("有线耳机" to 100L), "bluetooth" to ("蓝牙耳机" to 600L))
        val routes = if (physical) args.getString("bilingual_physical_routes", "speaker,wired,bluetooth")
            .split(',').map { requireNotNull(routeCatalog[it]) { "Unknown physical route" } }
            else listOf("合成输入" to 300L)
        require(routes.isNotEmpty() && routes.distinct().size == routes.size)
        val chinese = instrumentation.context.assets.open("aoq-chinese.pcm").use { it.readBytes() }
        val pcm = if (syntheticPair) chinese + ByteArray(64000) +
            instrumentation.context.assets.open("aoq-english.pcm").use { it.readBytes() } else chinese
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val readFailures = java.util.concurrent.atomic.AtomicInteger()
        val lastReadFailureClass = java.util.concurrent.atomic.AtomicReference<String>()
        val inputInterruptions = java.util.concurrent.atomic.AtomicInteger()
        val outputInterruptions = java.util.concurrent.atomic.AtomicInteger()
        val foregroundInterruptions = java.util.concurrent.atomic.AtomicInteger()
        val lastRead = java.util.concurrent.atomic.AtomicInteger()
        val sent = java.util.concurrent.atomic.AtomicInteger()
        val wireFailures = java.util.concurrent.atomic.AtomicInteger()
        val playbackFailures = java.util.concurrent.atomic.AtomicInteger()
        val playedSamples = java.util.concurrent.atomic.AtomicLong()
        val peakQueued = java.util.concurrent.atomic.AtomicLong()
        val lastAckAt = java.util.concurrent.atomic.AtomicLong()
        val received = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val errorCodes = ConcurrentHashMap.newKeySet<String>()
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
            val source = SyntheticSource(pcm,
                pauseBeforeSample = if (audibleSynthetic) chinese.size / 2 + 32000 else null,
                mayContinue = {
                    // Match the physical prompt: wait for the first response to
                    // drain before speaking English into the half-duplex input.
                    controller?.state?.value?.let { state ->
                        state.replayable.isNotEmpty() && playedSamples.get() > 0 && state.phase == BilingualPhase.LISTENING
                    } == true
                })
            val closed = AtomicBoolean()
            val tails = ConcurrentHashMap.newKeySet<Long>()
            main {
                controller = BilingualTranslationController(
                    instrumentation.targetContext, api, { !production || (account != null && app.captureAccount == account) },
                    openMicrophone = { interrupted ->
                        val input = if (physical) AndroidCapturePcmSource.open(instrumentation.targetContext).also {
                            it.onInterrupted = { inputInterruptions.incrementAndGet(); interrupted() }
                        } else source
                        object : CapturePcmSource by input {
                            override fun read(buffer: ShortArray): Int {
                                val count = try { input.read(buffer) } catch (error: Exception) {
                                    readFailures.incrementAndGet(); lastReadFailureClass.set(error.javaClass.simpleName); throw error
                                }
                                lastRead.set(count); reads.incrementAndGet()
                                return count
                            }
                            override fun close() { input.close(); closed.set(true) }
                        }
                    },
                    openOutput = { interrupted ->
                        val output = AndroidTranslationOutput(instrumentation.targetContext,
                            { outputInterruptions.incrementAndGet(); interrupted() }, startupBufferMs = 80)
                        object : BilingualAudioOutput {
                            override fun open() = output.open()
                            override fun play(samples: ShortArray) {
                                try { output.play(samples); playedSamples.addAndGet(samples.size.toLong()); tails.add(output.echoTailMs) }
                                catch (error: Exception) { playbackFailures.incrementAndGet(); throw error }
                            }
                            override fun finishTurn() {
                                try { output.finishTurn(); tails.add(output.echoTailMs) }
                                catch (error: Exception) { playbackFailures.incrementAndGet(); throw error }
                            }
                            override fun mute(muted: Boolean) = output.mute(muted)
                            override val pendingSamples get() = output.pendingSamples
                            override val echoTailMs get() = output.echoTailMs
                            override fun close() = output.close()
                        }
                    },
                    openWire = { url, listener ->
                        val observed = object : com.we.meet.data.capture.CaptureTranslationWire.Listener by listener {
                            override fun message(text: String) {
                                runCatching {
                                    val event = org.json.JSONObject(text)
                                    val type = event.optString("type")
                                    if (type in setOf("ready", "ack", "audio", "audio_end", "translation", "error", "expired", "finished", "language_unknown")) {
                                        received.computeIfAbsent(type) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                                    }
                                    if (type == "ack") lastAckAt.set(android.os.SystemClock.elapsedRealtime())
                                    val code = event.optString("code")
                                    if (type == "error" && code.matches(Regex("[a-z_]{1,64}"))) errorCodes.add(code)
                                }
                                listener.message(text)
                            }
                            override fun failed() { wireFailures.incrementAndGet(); listener.failed() }
                        }
                        val wire = if (production) OkHttpCaptureTranslationWire.open(url, observed)
                            else OkHttpCaptureTranslationWire.open(checkNotNull(socketClient), url, observed)
                        object : com.we.meet.data.capture.CaptureTranslationWire by wire {
                            override fun send(pcm: ByteArray): Boolean {
                                val accepted = wire.send(pcm)
                                if (accepted) sent.incrementAndGet()
                                return accepted
                            }
                            override val queuedBytes get() = wire.queuedBytes.also { size -> peakQueued.accumulateAndGet(size) { a, b -> maxOf(a, b) } }
                        }
                    },
                    openForeground = { stopped ->
                        if (production) com.we.meet.feature.assistant.background.AssistantForegroundSession.start(
                            instrumentation.targetContext, com.we.meet.feature.assistant.background.AssistantSessionKind.TRANSLATION, false,
                            { foregroundInterruptions.incrementAndGet(); stopped() })
                        else object : AssistantSessionLease {
                        override suspend fun camera(enabled: Boolean) = Unit
                        override fun close() = Unit
                    } },
                )
            }
            val active = controller!!
            awaitCondition { active.state.value.voiceConfig != null || active.state.value.unavailableReason != null }
            assertNull(active.state.value.unavailableReason)
            main { active.fixedSource(if (automatic) null else "zh"); active.voice("Ethan"); active.sound(physical || audibleSynthetic) }
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
            if (!physical) {
                val hold = args.getString("bilingual_hold_seconds", "0").toLong()
                require(hold in 0..60)
                runBlocking { delay(hold * 1000) }
                assertTrue(active.state.value.active)
            }
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
            if (audibleSynthetic) assertTrue("Immediate playback must run before replay", playedSamples.get() > 0)
            main { active.replay(row.id) }
            awaitCondition { !active.state.value.replaying }
            assertFalse(active.state.value.replayError)
            assertTrue(row.id in active.state.value.replayable)
            if (physical) assertTrue("Output route does not match $route", expectedTail in tails)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_result", "production=$production route=$route mode=${if (automatic) "automatic" else "fixed"} environment=${if (backgroundNoise) "background_noise" else "quiet"} rows=$expectedRows explicit_audio_end=true replay=true tails=${tails.sorted()}")
            })
            main { active.close(); controller = null }
          }
        } finally {
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_diagnostics", org.json.JSONObject().apply {
                    put("reads", reads.get()); put("last_read_samples", lastRead.get())
                    put("read_failures", readFailures.get()); put("last_read_failure_class", lastReadFailureClass.get())
                    put("input_interruptions", inputInterruptions.get()); put("output_interruptions", outputInterruptions.get())
                    put("foreground_interruptions", foregroundInterruptions.get())
                    put("sent_frames", sent.get()); put("wire_failures", wireFailures.get())
                    put("playback_failures", playbackFailures.get()); put("peak_queued_bytes", peakQueued.get())
                    put("played_samples", playedSamples.get())
                    put("last_ack_age_ms", if (lastAckAt.get() == 0L) -1 else android.os.SystemClock.elapsedRealtime() - lastAckAt.get())
                    put("events", org.json.JSONObject(received.mapValues { it.value.get() }))
                    put("error_codes", org.json.JSONArray(errorCodes.toList()))
                    put("phase", controller?.state?.value?.phase?.name ?: "CLOSED")
                    put("failure_reason", controller?.state?.value?.failure?.name)
                }.toString())
            })
            main { controller?.close() }
            activity.close()
            apiClient?.connectionPool?.evictAll()
            socketClient?.connectionPool?.evictAll()
            socketClient?.dispatcher?.executorService?.shutdown()
        }
    }

    @Test fun productionDisabledAdmissionRejectsNewAndLegacySessions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bilingual_production_disabled") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as com.we.meet.WeMeetApp
        check(app.captureAccount != null)
        val api = app.apiClient.assistantTranslationApi
        val config = api.voiceConfig()
        assertEquals(2, config.protocolVersion)
        assertFalse(config.enabled)
        try {
            api.ticket(com.we.meet.data.api.AssistantTranslationRequest("zh", "en", voice = "Ethan"))
            fail("Disabled admission must reject a new session")
        } catch (error: retrofit2.HttpException) {
            assertEquals(403, error.code())
        }
        val base = com.we.meet.BuildConfig.WE_MEET_BASE_URL.trimEnd('/')
        require(base.startsWith("https://"))
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        try {
            val request = okhttp3.Request.Builder().url("$base/api/v1.0/assistant-translation/session/")
                .header("Authorization", "Bearer ${checkNotNull(app.tokenStore.accessToken)}")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { assertEquals(426, it.code) }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("bilingual_gate_result", "production=true enabled=false ticket_status=403 legacy_status=426")
        })
    }

    @Test fun productionFeatureDisableReleasesActiveSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bilingual_production_rollback") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as com.we.meet.WeMeetApp
        val account = checkNotNull(app.captureAccount)
        val api = app.apiClient.assistantTranslationApi
        assertTrue(runBlocking { api.voiceConfig() }.enabled)
        val source = SyntheticSource(ByteArray(0))
        var controller: BilingualTranslationController? = null
        val activity = ActivityScenario.launch(com.we.meet.MainActivity::class.java)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitCondition(timeout: Long, condition: () -> Boolean) = runBlocking {
            withTimeout(timeout) { while (!condition()) delay(25) }
        }
        try {
            main { controller = BilingualTranslationController(instrumentation.targetContext, api,
                { app.captureAccount == account }, openMicrophone = { source }) }
            val active = checkNotNull(controller)
            awaitCondition(30000) { active.state.value.voiceConfig != null }
            main { active.fixedSource("zh"); active.sound(false); active.start() }
            awaitCondition(60000) { active.state.value.phase == BilingualPhase.LISTENING || !active.state.value.active }
            assertEquals(BilingualPhase.LISTENING, active.state.value.phase)
            val readyAt = android.os.SystemClock.elapsedRealtime()
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_rollback_ready", "production=true active=true synthetic_silence=true")
            })
            awaitCondition(240000) { !active.state.value.active }
            assertTrue(active.state.value.phase in setOf(BilingualPhase.ERROR, BilingualPhase.EXPIRED))
            assertTrue(source.closed.get())
            assertTrue(active.state.value.rows.isEmpty())
            awaitCondition(120000) { runBlocking { runCatching { !api.voiceConfig().enabled }.getOrDefault(false) } }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_rollback_result", "production=true active=false input_released=true enabled=false elapsed_ms=${android.os.SystemClock.elapsedRealtime() - readyAt}")
            })
        } finally {
            main { controller?.close() }
            activity.close()
        }
    }

    private class SyntheticSource(
        pcm: ByteArray,
        private val pauseBeforeSample: Int? = null,
        private val mayContinue: () -> Boolean = { true },
    ) : CapturePcmSource {
        private val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        private var passedBoundary = pauseBeforeSample == null
        val closed = AtomicBoolean()
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            Thread.sleep(buffer.size * 1000L / 16000)
            if (closed.get()) return 0
            buffer.fill(0)
            if (!passedBoundary && samples.position() >= checkNotNull(pauseBeforeSample)) {
                if (!mayContinue()) return buffer.size
                passedBoundary = true
            }
            val count = minOf(buffer.size, samples.remaining(),
                if (passedBoundary) buffer.size else checkNotNull(pauseBeforeSample) - samples.position())
            samples.get(buffer, 0, count)
            return buffer.size
        }
        override fun stop() { closed.set(true) }
        override fun close() { stop() }
    }
}
