package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CapturePlaylist
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAudioPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = UUID.randomUUID().toString()
    private val playlist = CapturePlaylist(record, UUID.randomUUID().toString(), 4,
        CaptureManifestDto(2, "incomplete", 2000, emptyList(), listOf(CaptureGapDto(1000, 2000))),
        listOf(0L, 2000L).mapIndexed { index, start -> CaptureAudioReceiptDto(UUID.randomUUID().toString(), index + 1,
            start, 1000, "0".repeat(64), 32044, true) })
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val downloads = CopyOnWriteArrayList<Int>()
    private val seek = mutableStateOf<CaptureAudioSeek?>(null)
    @Volatile private var allowed = true
    @Volatile private var fail = false
    @Volatile private var reads = 0
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface {
            CaptureAudioPlayer("fixture", record, { reads++; check(!fail); playlist }, { guard ->
                CapturePlaybackEngine({ _, index -> downloads += index; CaptureWave.encode(ShortArray(16000)) }, {},
                    { Sink().also { sinks += it } }, guard)
            }, { allowed }, seek.value, { seek.value = null })
        } } }
        await(R.string.capture_playback_play)
    }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "capture-playback-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun openingDoesNotDownloadAndExplicitPauseClosesWithoutResume() {
        show()
        assertTrue(downloads.isEmpty())
        screenshot("ready")
        compose.onNodeWithText(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        compose.onNodeWithText(label(R.string.capture_playback_pause)).performClick()
        assertTrue(sinks.single().closed)
        assertEquals(listOf(0), downloads.toList())
        compose.waitForIdle()
        assertEquals(1, sinks.size)
    }
    @Test fun exactSourceSeekAndRateSelectionRequireExplicitPlayback() {
        show()
        compose.runOnIdle { seek.value = CaptureAudioSeek(2300) }
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        assertEquals(300L, sinks.first().offset)
        assertEquals(listOf(1), downloads.toList())
        val normal = context.getString(R.string.capture_playback_rate, 1f)
        compose.onNodeWithText(normal).performClick()
        assertTrue(sinks.first().closed)
        compose.onNodeWithText(context.getString(R.string.capture_playback_rate, 1.5f)).performClick()
        assertEquals(1, sinks.size)
        compose.onNodeWithText(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8000) { sinks.size == 2 && sinks.last().started }
        assertEquals(1.5f, sinks.last().rate)
    }
    @Test fun seekingMissingAudioDoesNotDownloadAndNextSegmentIsExplicit() {
        show(dark = true)
        compose.runOnIdle { seek.value = CaptureAudioSeek(1500) }
        await(R.string.capture_playback_gap)
        assertTrue(downloads.isEmpty())
        screenshot("gap")
        compose.onNodeWithText(label(R.string.capture_playback_skip)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        assertEquals(listOf(1), downloads.toList())
        assertEquals(0L, sinks.single().offset)
    }
    @Test fun backgroundClosesAndReturnRechecksWithoutAutoplay() {
        show()
        compose.onNodeWithText(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(sinks.single().closed)
        val previousReads = reads
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.capture_playback_play)
        assertTrue(reads > previousReads)
        assertEquals(1, sinks.size)
    }
    @Test fun accountLossStopsAndFailedReloadCannotReplayOldPlaylist() {
        show()
        compose.onNodeWithText(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        allowed = false
        await(R.string.capture_playback_error)
        assertTrue(sinks.single().closed)
        compose.onNodeWithText(label(R.string.capture_playback_play)).assertDoesNotExist()
        screenshot("error")
        fail = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.capture_playback_error)
        assertEquals(1, sinks.size)
    }
    @Test fun recorderExclusionClosesRegisteredPlayerImmediately() {
        show()
        compose.onNodeWithText(label(R.string.capture_playback_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        CapturePlaybackRegistry.stopAll()
        assertTrue(sinks.single().closed)
        await(R.string.capture_playback_error)
        assertEquals(1, sinks.size)
    }
    private class Sink : CapturePlaybackOutput {
        @Volatile var closed = false
        @Volatile var started = false
        var offset = 0L
        var rate = 1f
        override fun play(wave: ByteArray, offsetMs: Long, rate: Float) { check(!closed); offset = offsetMs; this.rate = rate; started = true }
        override fun positionMs() = offset
        override fun close() { closed = true }
    }
}
