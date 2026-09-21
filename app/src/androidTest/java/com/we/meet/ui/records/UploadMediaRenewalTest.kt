package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.UploadMediaEngine
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native decoding over HTTP; deterministic 403 fixtures do not prove a production TTL. */
@RunWith(AndroidJUnit4::class)
class UploadMediaRenewalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String?, Int>>()
    private val engines = CopyOnWriteArrayList<ObservedEngine>()
    private val positions = CopyOnWriteArrayList<Long>()
    private val seek = mutableStateOf<CaptureAudioSeek?>(null)
    private lateinit var currentMedia: androidx.compose.runtime.MutableState<RecordMediaDto>
    @Volatile private var expired = false
    @Volatile private var denyFresh = false
    @Volatile private var refusedGate: CountDownLatch? = null

    private fun label(id: Int) = context.getString(id)
    private fun speed(value: Float) = context.getString(R.string.capture_playback_rate, value)

    @Before fun serveAudio() {
        val pcmBytes = 16000 * 2 * 30
        val buffer = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(36 + pcmBytes); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(16000)
        buffer.putInt(32000); buffer.putShort(2); buffer.putShort(16)
        buffer.put("data".toByteArray()); buffer.putInt(pcmBytes)
        val wav = buffer.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (expired && request.path == "/old.wav" || denyFresh && request.path?.startsWith("/fresh.wav") == true) {
                    requests += request.path to 403
                    refusedGate?.await(8, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(403)
                        .setHeader("Content-Type", "application/xml")
                        .setHeader("Connection", "close")
                        .setBody("<Error><Code>AccessDenied</Code><Message>Expired lease fixture</Message></Error>")
                }
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val start = range?.groupValues?.get(1)?.toInt() ?: 0
                val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(wav.lastIndex) ?: wav.lastIndex
                if (start > end) return MockResponse().setResponseCode(416)
                val status = if (range == null) 200 else 206
                requests += request.path to status
                return MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", "audio/wav")
                    .setHeader("Accept-Ranges", "bytes")
                    .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${wav.size}") }
                    .setBody(Buffer().write(wav, start, end - start + 1))
            }
        }
        server.start()
        currentMedia = mutableStateOf(RecordMediaDto(server.url("/old.wav").toString(), 3600,
            "audio", "renewal.wav", wav.size.toLong(), "audio/wav"))
    }

    @After fun closeAudio() {
        refusedGate?.countDown()
        compose.runOnUiThread { engines.forEach { it.close() } }
        server.shutdown()
    }

    private fun show() {
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(currentMedia.value, 4_000, seek.value,
                        onSeekConsumed = { seek.value = null }, onPosition = { positions += it },
                        preparationTimeoutMs = 1_500,
                        createEngine = { url, interrupted ->
                            ObservedEngine(url, UploadMediaEngine(context, url, interrupted)).also { engines += it }
                        })
                }
            }
        }
        compose.onNodeWithText(speed(1f)).performClick()
        compose.onNodeWithText(speed(1.5f)).performClick()
    }

    private fun renew() = compose.runOnIdle {
        currentMedia.value = currentMedia.value.copy(url = server.url("/fresh.wav").toString())
    }

    private fun play() = compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
    private fun pause() = compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
    private fun awaitCondition(condition: () -> Boolean) {
        try { compose.waitUntil(30_000, condition) }
        catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("requests=$requests, preparing=${engines.lastOrNull()?.isPreparing()}, failure=${engines.lastOrNull()?.failure()}", error)
        }
    }
    private fun awaitFreshPlayback() {
        awaitCondition { engines.lastOrNull()?.let { it.url.endsWith("/fresh.wav") && it.isPlaying() } == true }
        assertEquals(null, engines.last().failure())
        assertEquals(1.5f, engines.last().lastRate)
        assertTrue(requests.any { it.first == "/fresh.wav" && it.second in listOf(200, 206) })
        compose.onNodeWithText(speed(1.5f)).assertIsDisplayed()
    }

    @Test fun renewedHttpLeasePreservesPausedPositionRateAndExactSeek() {
        show()
        play()
        compose.waitUntil(10_000) { positions.any { it >= 4_000 } && engines.lastOrNull()?.isPlaying() == true }
        pause()
        val pausedPosition = positions.last()
        val first = engines.single()
        expired = true
        renew()
        compose.waitForIdle()
        assertEquals(1, engines.size)
        assertEquals(false, first.isPlaying())
        assertEquals(pausedPosition, positions.last())
        assertFalse(requests.any { it.first == "/fresh.wav" })
        play()
        awaitFreshPlayback()
        assertEquals(pausedPosition, engines.last().lastFrom)
        assertTrue(first.closed)
        compose.runOnIdle { seek.value = CaptureAudioSeek(12_500) }
        compose.waitUntil(10_000) { engines.last().lastFrom == 12_500L && positions.any { it >= 12_500 } }
        assertEquals(1.5f, engines.last().lastRate)
    }

    @Test fun realHttp403RetriesFromPreservedPositionAfterLeaseArrives() {
        expired = true
        show()
        play()
        awaitCondition {
            compose.onAllNodesWithText(label(R.string.capture_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(requests.any { it.second == 403 })
        renew()
        compose.onNodeWithText(label(com.we.meet.design.R.string.common_retry)).performClick()
        awaitFreshPlayback()
        assertEquals(4_000L, engines.last().lastFrom)
    }

    @Test fun realHttp403AutomaticallyUsesAnAlreadyRefreshedLease() {
        expired = true
        refusedGate = CountDownLatch(1)
        show()
        play()
        compose.waitUntil(5_000) { requests.any { it.second == 403 } }
        renew()
        refusedGate!!.countDown()
        awaitFreshPlayback()
        assertEquals(4_000L, engines.last().lastFrom)
        compose.onNodeWithText(label(R.string.capture_playback_error)).assertDoesNotExist()
    }

    @Test fun repeatedNewLeasesCannotKeepAFailedStreamLoadingForever() {
        expired = true
        denyFresh = true
        show()
        play()
        compose.waitUntil(5_000) { requests.any { it.second == 403 } }
        renew()
        compose.waitUntil(10_000) { engines.size == 2 }
        compose.runOnIdle {
            currentMedia.value = currentMedia.value.copy(url = server.url("/fresh.wav?next=1").toString())
        }
        awaitCondition {
            compose.onAllNodesWithText(label(R.string.capture_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Only one automatic replacement per failed preparation", 2, engines.size)
        assertTrue(engines.all { it.closed })
    }

    private class ObservedEngine(val url: String, private val real: WholeFilePlayback) : WholeFilePlayback by real {
        @Volatile var lastFrom: Long? = null
        @Volatile var lastRate: Float? = null
        @Volatile var closed = false
        override fun play(fromMs: Long, rate: Float) {
            lastFrom = fromMs
            lastRate = rate
            real.play(fromMs, rate)
        }
        override fun close() { closed = true; real.close() }
    }
}
