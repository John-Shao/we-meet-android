package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import java.io.File
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.theme.WeMeetTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole-file player's contract with the transcript: report the source clock
 * so text can follow, and accept an exact millisecond so a citation can seek.
 * Both are driven through a fake engine, since a real one would open a stream.
 */
@RunWith(AndroidJUnit4::class)
class UploadMediaPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = RecordMediaDto(
        url = "https://private.example/import.m4a?sig=fixture",
        expiresIn = 3600,
        mediaType = "audio",
        name = "import.m4a",
        size = 4096,
        contentType = "audio/mp4",
    )
    private val currentMedia = mutableStateOf(media)
    private val openedUrls = CopyOnWriteArrayList<String>()
    private val reported = CopyOnWriteArrayList<Long>()
    private val durations = CopyOnWriteArrayList<Long?>()
    private val seek = mutableStateOf<CaptureAudioSeek?>(null)
    private var engine: FakeEngine? = null
    private val follow = TranscriptFollowState()

    private fun label(id: Int) = context.getString(id)
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show() {
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(
                        media = currentMedia.value,
                        followState = follow,
                        positionMs = null,
                        seek = seek.value,
                        onSeekConsumed = { seek.value = null },
                        onPosition = { reported += it },
                        onDuration = { durations += it },
                        createEngine = { url, onInterrupted ->
                            openedUrls += url
                            FakeEngine(onInterrupted).also { engine = it }
                        },
                    )
                }
            }
        }
    }

    @Test fun reportsTheSourceClockAndAcceptsAnExactSeek() {
        show()
        // Opening must not start playback: a GB-scale import is not fetched on open.
        assertTrue(engine == null)
        val play = compose.onNodeWithContentDescription(label(R.string.cd_records_play))
        play.assertIsDisplayed()
        play.performClick()
        compose.waitForIdle()
        assertTrue("playback starts only when asked", engine?.playing == true)

        // A citation addresses an exact millisecond and that is what playback gets.
        compose.runOnIdle { seek.value = CaptureAudioSeek(42_500) }
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 42_500L }
        assertEquals(42_500L, engine?.lastPlayFrom)
    }

    @Test fun explicitSeekRestoresFollowButProtectsAnActiveEdit() {
        show()
        compose.runOnIdle { follow.following = false; seek.value = CaptureAudioSeek(12_000) }
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 12_000L }
        compose.runOnIdle { assertTrue(follow.following); follow.following = false; follow.canResume = { false } }
        compose.onNodeWithContentDescription(label(R.string.cd_records_skip_forward)).performClick()
        compose.runOnIdle { assertEquals(false, follow.following); follow.canResume = { true } }
        compose.onNodeWithContentDescription(label(R.string.cd_records_skip_back)).performClick()
        compose.runOnIdle { assertTrue(follow.following) }
    }

    @Test fun aPositionTickReachesTheTranscriptThroughTheCallback() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        // The poller reports the engine's clock; drive it and assert the tick lands.
        compose.runOnIdle { engine?.clock = 7_000L }
        compose.waitUntil(8_000) { reported.contains(7_000L) }
        assertTrue(reported.contains(7_000L))
        assertEquals(engine?.durationMs(), durations.last())
    }

    @Test fun pausingStopsWithoutDiscardingThePosition() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
        compose.waitForIdle()
        assertEquals(false, engine?.playing)
        // Resume control is offered again at the same place.
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).assertIsDisplayed()
    }

    @Test fun aFailedEngineSurfacesAnErrorInsteadOfASilentNoOp() {
        compose.setContent {
            WeMeetTheme {
                Surface {
                    UploadMediaPlayer(
                        media = media,
                        positionMs = null,
                        onPosition = { reported += it },
                        createEngine = { _, _ -> throw IllegalStateException("refused") },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(label(R.string.capture_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.capture_playback_error)).assertIsDisplayed()
    }

    @Test fun refreshedLeaseDoesNotRestartPlaybackAndNextSeekUsesLatestUrl() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.runOnIdle { engine?.clock = 42_000L }
        compose.waitUntil(8_000) { reported.contains(42_000L) }
        val originalEngine = engine
        compose.runOnIdle { currentMedia.value = media.copy(url = "https://private.example/renewed") }
        compose.waitForIdle()
        assertTrue(engine === originalEngine)
        assertEquals(1, openedUrls.size)
        assertEquals(true, engine?.playing)
        compose.runOnIdle { seek.value = CaptureAudioSeek(60_000) }
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 60_000L }
        assertEquals("https://private.example/renewed", openedUrls.last())
        assertEquals(false, originalEngine?.playing)
    }

    @Test fun refreshedLeasePreservesAPausedPosition() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.runOnIdle { engine?.clock = 42_000L }
        compose.waitUntil(8_000) { reported.contains(42_000L) }
        compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
        compose.runOnIdle { currentMedia.value = media.copy(url = "https://private.example/renewed") }
        compose.waitForIdle()
        assertEquals(1, openedUrls.size)
        assertEquals(false, engine?.playing)
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { openedUrls.size == 2 }
        assertEquals(42_000L, engine?.lastPlayFrom)
    }

    @Test fun videoUsesARealSurfaceWithoutChangingThePlaybackContract() {
        currentMedia.value = media.copy(mediaType = "video", contentType = "video/mp4")
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        assertTrue(engine?.playing == true)
    }

    @Test fun pausedVideoKeepsItsSurfaceAndSeekingDoesNotReopenTheStream() {
        currentMedia.value = media.copy(mediaType = "video", contentType = "video/mp4")
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        val originalSurface = engine!!.outputSurface
        compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_video_preview)).assertIsDisplayed()
        assertTrue(originalSurface === engine!!.outputSurface)
        compose.onNodeWithContentDescription(label(R.string.capture_playback_position)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) { it(30_000f) }
        compose.runOnIdle {
            assertEquals(30_000L, engine!!.clock)
            assertEquals(false, engine!!.playing)
            assertEquals(1, openedUrls.size)
            assertTrue(originalSurface === engine!!.outputSurface)
        }
    }

    @Test fun videoCollapseAndFullscreenKeepTheSamePlaybackSession() {
        currentMedia.value = media.copy(mediaType = "video", contentType = "video/mp4")
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        compose.onNodeWithText(label(R.string.capture_playback_hide_video)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface == null }
        assertTrue(engine!!.playing)
        compose.onNodeWithText(label(R.string.capture_playback_show_video)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        compose.onNodeWithContentDescription(label(R.string.capture_playback_fullscreen)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_exit_fullscreen)).assertIsDisplayed()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_exit_fullscreen)).performClick()
        compose.waitUntil(8_000) { engine?.outputSurface?.isValid == true }
        assertEquals(false, engine!!.playing)
        assertEquals(1, openedUrls.size)
    }

    @Test fun nativeVideoSurfaceFitsPortraitAndLandscapeWithoutDistortion() {
        currentMedia.value = media.copy(mediaType = "video", contentType = "video/mp4")
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        fun findSurface(view: android.view.View): android.view.SurfaceView? =
            if (view is android.view.SurfaceView) view else if (view is android.view.ViewGroup) {
                (0 until view.childCount).firstNotNullOfOrNull { findSurface(view.getChildAt(it)) }
            } else null
        for (ratio in listOf(9f / 16f, 16f / 9f)) {
            compose.runOnIdle { engine!!.aspect = ratio }
            compose.waitUntil(8_000) {
                findSurface(compose.activity.window.decorView)?.let {
                    it.height > 0 && kotlin.math.abs(it.width.toFloat() / it.height - ratio) < 0.01f
                } == true
            }
            compose.runOnIdle {
                val surface = requireNotNull(findSurface(compose.activity.window.decorView))
                val heightCap = context.resources.configuration.screenHeightDp * context.resources.displayMetrics.density * 0.3f
                assertTrue("native surface must respect the height cap", surface.height <= heightCap + 1)
            }
            compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).assertIsDisplayed()
        }
    }

    @Test fun realEnginePreparesAsynchronouslyAndReportsItsClock() {
        show()
        val file = java.io.File.createTempFile("playback-", ".wav", context.cacheDir)
        val pcmBytes = 16000 * 2 * 5
        val buffer = java.nio.ByteBuffer.allocate(44 + pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(36 + pcmBytes); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(16000)
        buffer.putInt(32000); buffer.putShort(2); buffer.putShort(16)
        buffer.put("data".toByteArray()); buffer.putInt(pcmBytes)
        file.writeBytes(buffer.array())
        var real: com.we.meet.data.capture.UploadMediaEngine? = null
        try {
            compose.runOnIdle {
                real = com.we.meet.data.capture.UploadMediaEngine(context, file.absolutePath) {}
                real!!.play(1000, 1.5f)
                assertTrue("prepare must return before the callback", real!!.isPreparing())
                // The real UI reads duration immediately after play(). Native
                // getDuration in Preparing reports an async -38 error instead
                // of throwing, so runCatching alone cannot protect playback.
                assertEquals(0L, real!!.durationMs())
                assertEquals(false, real!!.isPlaying())
            }
            compose.waitUntil(8_000) { real?.isPlaying() == true || real?.failure() != null }
            assertEquals(null, real?.failure())
            assertTrue(real!!.durationMs() >= 5000)
            compose.waitUntil(8_000) { real!!.positionMs() >= 1000 }
            compose.runOnIdle { real!!.pause() }
            assertEquals(false, real?.isPlaying())
        } finally {
            compose.runOnIdle { real?.close() }
            file.delete()
        }
    }

    @Test fun importedAudioUsesTheSameMuteControlWithoutRestartingPlayback() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_mute)).performClick()
        assertEquals(null, engine)
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { engine?.mutedOutput == true }
        val original = engine
        compose.onNodeWithContentDescription(label(R.string.capture_playback_unmute)).performClick()
        compose.runOnIdle {
            assertEquals(false, engine?.mutedOutput)
            assertTrue(original === engine)
            assertEquals(1, openedUrls.size)
        }
    }

    @Test fun videoMuteAndSkipControlsReachTheEngineAndSurviveSurfaceChanges() {
        currentMedia.value = media.copy(mediaType = "video", contentType = "video/mp4")
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_mute)).performClick()
        assertEquals(null, engine)
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8_000) { engine?.mutedOutput == true }
        val original = engine
        compose.runOnIdle { seek.value = CaptureAudioSeek(45_000) }
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 45_000L && reported.contains(45_000L) }
        compose.onNodeWithContentDescription(label(R.string.cd_records_skip_back)).performClick()
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 30_000L && reported.contains(30_000L) }
        compose.onNodeWithContentDescription(label(R.string.cd_records_skip_forward)).performClick()
        compose.waitUntil(8_000) { engine?.lastPlayFrom == 45_000L }
        compose.onNodeWithText(label(R.string.capture_playback_hide_video)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_unmute)).performClick()
        compose.runOnIdle { assertEquals(false, engine?.mutedOutput); assertTrue(original === engine) }
        compose.onNodeWithContentDescription(label(R.string.capture_playback_mute)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_position))
            .performTouchInput { swipe(Offset(width * 0.25f, height * 0.75f), Offset(width * 0.75f, height * 0.75f)) }
        compose.runOnIdle { assertTrue(original === engine); assertTrue(engine!!.clock > 45_000L) }
        File(context.getExternalFilesDir(null), "record-player-collapsed-video.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(label(R.string.capture_playback_show_video)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_fullscreen)).performClick()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_unmute)).performClick()
        compose.runOnIdle {
            assertTrue(original === engine)
            assertEquals(false, engine?.mutedOutput)
        }
    }

    /** A whole-file engine with no stream behind it. */
    private class FakeEngine(private val onInterrupted: () -> Unit) : WholeFilePlayback {
        @Volatile var outputSurface: android.view.Surface? = null
        override fun setSurface(surface: android.view.Surface?) { outputSurface = surface }
        @Volatile var playing = false
        @Volatile var mutedOutput = false
        override fun setMuted(muted: Boolean) { mutedOutput = muted }
        @Volatile var clock = 0L
        @Volatile var lastPlayFrom: Long? = null
        @Volatile var duration = 120_000L
        @Volatile var aspect = 16f / 9f
        override fun videoAspectRatio() = aspect
        override fun durationMs() = duration
        override fun isPlaying() = playing
        override fun positionMs() = clock
        override fun play(fromMs: Long, rate: Float) {
            lastPlayFrom = fromMs
            clock = fromMs
            playing = true
        }
        override fun pause() { playing = false }
        override fun seekTo(milliseconds: Long) { clock = milliseconds }
        override fun close() { playing = false }
    }
}
