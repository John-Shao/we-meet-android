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
    private val previewStopToken = mutableStateOf<String?>(null)
    /** Every source-clock position the player reported, in order. */
    private val reported = CopyOnWriteArrayList<Long>()
    @Volatile private var allowed = true
    @Volatile private var fail = false
    @Volatile private var reads = 0
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodes(hasText(label(id)) or hasContentDescription(label(id))).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface {
            CaptureAudioPlayer("fixture", record, { reads++; check(!fail); playlist }, { guard ->
                CapturePlaybackEngine({ _, index -> downloads += index; CaptureWave.encode(ShortArray(16000)) }, {},
                    { Sink().also { sinks += it } }, guard)
            }, { allowed }, seek.value, { seek.value = null }, { reported += it }, previewStopToken = previewStopToken.value)
        } } }
        await(R.string.cd_records_play)
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
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        compose.onNodeWithContentDescription(label(R.string.cd_records_pause)).performClick()
        assertTrue(sinks.single().closed)
        assertEquals(listOf(0), downloads.toList())
        compose.waitForIdle()
        assertEquals(1, sinks.size)
    }
    @Test fun closingAnUnusedIdentityPreviewDoesNotStopNormalPlayback() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.single().started }
        compose.runOnIdle { previewStopToken.value=UUID.randomUUID().toString() }
        compose.waitForIdle()
        assertFalse(sinks.single().closed)
    }
    @Test fun accountRevocationClearsAQuietCachedPlaylist() {
        show()
        allowed=false
        await(R.string.capture_playback_error)
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).assertDoesNotExist()
        assertTrue(downloads.isEmpty())
    }
    @Test fun nativeSyntheticPreviewCrossesTwoChunksAndCannotPlayTheNextSpeaker() {
        val chunked=CapturePlaylist(record,UUID.randomUUID().toString(),1,
            CaptureManifestDto(3,"saved",3000,emptyList(),emptyList()),(0..2).map { index ->
                CaptureAudioReceiptDto(UUID.randomUUID().toString(),index+1,index*1000L,1000,"0".repeat(64),32044,true) })
        val durations=CopyOnWriteArrayList<Long>()
        val closed=java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent { WeMeetTheme { Surface {
            CaptureAudioPlayer("fixture",record,{chunked},{ guard ->
                CapturePlaybackEngine({ _, index -> downloads += index; CaptureWave.encode(ShortArray(16000)) },{}, { interrupted ->
                    val native=AndroidCapturePlaybackOutput(context,interrupted)
                    object : CapturePlaybackOutput {
                        override fun play(wave: ByteArray,offsetMs: Long,rate: Float) { durations += CaptureWave.inspect(wave).durationMs; native.play(wave,offsetMs,rate) }
                        override fun positionMs()=native.positionMs()
                        override fun close() { native.close(); closed.set(true) }
                    }
                },guard)
            },{true},seek.value,{seek.value=null},{reported += it})
        } } }
        await(R.string.cd_records_play)
        compose.runOnIdle { seek.value=CaptureAudioSeek(500,endMs=1500) }
        compose.waitUntil(10000) { closed.get() && reported.lastOrNull() == 1500L }
        assertEquals(listOf(0,1),downloads.toList())
        assertEquals(listOf(1000L,500L),durations.toList())
    }
    @Test fun exactSourceSeekAndRateSelectionPreservePlayback() {
        show()
        compose.runOnIdle { seek.value = CaptureAudioSeek(2300) }
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        assertEquals(300L, sinks.first().offset)
        assertEquals(listOf(1), downloads.toList())
        val normal = context.getString(R.string.capture_playback_rate, playbackRateValue(1f))
        compose.onNodeWithText(normal).performClick()
        assertFalse("opening the speed menu must not stop audio", sinks.first().closed)
        assertEquals(1, sinks.size)
        compose.onNodeWithText(context.getString(R.string.capture_playback_rate, playbackRateValue(1.5f))).performClick()
        compose.waitUntil(8000) { sinks.size == 2 && sinks.last().started }
        assertTrue(sinks.first().closed)
        assertEquals(300L, sinks.last().offset)
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
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(sinks.single().closed)
        val previousReads = reads
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.cd_records_play)
        assertTrue(reads > previousReads)
        assertEquals(1, sinks.size)
    }
    @Test fun accountLossStopsAndFailedReloadCannotReplayOldPlaylist() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        allowed = false
        await(R.string.capture_playback_error)
        assertTrue(sinks.single().closed)
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).assertDoesNotExist()
        screenshot("error")
        fail = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.capture_playback_error)
        assertEquals(1, sinks.size)
    }
    @Test fun recorderExclusionClosesRegisteredPlayerImmediately() {
        show()
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        CapturePlaybackRegistry.stopAll()
        assertTrue(sinks.single().closed)
        await(R.string.capture_playback_error)
        assertEquals(1, sinks.size)
    }
    @Test fun playerReportsTheSourceClockSoATranscriptCanFollow() {
        // The transcript derives its active row from these numbers, so the player
        // must report in the recording's own clock — not the current chunk's.
        show()
        compose.runOnIdle { seek.value = CaptureAudioSeek(2000) }
        compose.waitUntil(8000) { reported.contains(2000L) }
        assertTrue(reported.contains(2000L))
    }

    @Test fun draggingTheTimelineAlsoReportsTheNewPosition() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_position)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) { it(1000f) }
        compose.runOnIdle { }
        assertTrue(reported.isNotEmpty())
        assertTrue(reported.last() >= 1000L)
    }

    @Test fun muteWorksBeforePlaybackAndSurvivesSeeking() {
        show()
        compose.onNodeWithContentDescription(label(R.string.capture_playback_mute)).performClick()
        assertTrue(downloads.isEmpty())
        compose.onNodeWithContentDescription(label(R.string.cd_records_play)).performClick()
        compose.waitUntil(8000) { sinks.isNotEmpty() && sinks.first().started }
        assertTrue(sinks.first().mutedOutput)
        compose.onNodeWithContentDescription(label(R.string.capture_playback_unmute)).performClick()
        compose.runOnIdle { assertFalse(sinks.first().mutedOutput); assertEquals(1, sinks.size) }
        compose.onNodeWithContentDescription(label(R.string.capture_playback_mute)).performClick()
        compose.runOnIdle { seek.value = CaptureAudioSeek(2300) }
        compose.waitUntil(8000) { sinks.size == 2 && sinks.last().started }
        assertTrue(sinks.last().mutedOutput)
    }

    private class Sink : CapturePlaybackOutput {
        @Volatile var closed = false
        @Volatile var started = false
        @Volatile var mutedOutput = false
        override fun setMuted(muted: Boolean) { mutedOutput = muted }
        var offset = 0L
        var rate = 1f
        override fun play(wave: ByteArray, offsetMs: Long, rate: Float) { check(!closed); offset = offsetMs; this.rate = rate; started = true }
        override fun positionMs() = offset
        override fun close() { closed = true }
    }
}
