package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.MeetingSummaryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.MeetingSummaryRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordSummaryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "summary-ui-${UUID.randomUUID()}"
    private val capture = UUID.randomUUID().toString()
    private val record = RecordDto(UUID.randomUUID().toString(), "audio_recording", "Fixture review", "2026-09-13T00:00:00Z", 3,
        RecordCapabilitiesDto(true, true, true), captureId = capture, isOngoing = true)
    private val api = Fixture()
    private val repository = MeetingSummaryRepository(api) { viewer }
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(id: Int) {
        await(id)
        compose.waitUntil(8000) { !compose.onNodeWithText(label(id)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithText(label(id)).performScrollTo().performClick()
    }
    private fun show(dark: Boolean = false) { compose.setContent { WeMeetTheme(darkTheme = dark) { Column(Modifier.verticalScroll(rememberScrollState())) {
        RecordSummaryControls(viewer, record, repository) { viewer }
    } } } }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "summary-controls-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun onlyReadyStageCanBeExplicitlyRequestedAndNoAutomaticEffects() {
        show()
        await(R.string.summary_controls_realtime)
        compose.onNodeWithText(label(R.string.summary_controls_quick)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.summary_controls_final)).assertDoesNotExist()
        assertTrue(api.keys.isEmpty())
        screenshot("ready")
        click(R.string.summary_controls_realtime)
        await(R.string.summary_job_queued)
        assertEquals(1, api.keys.size)
        assertTrue(api.bodies.single().contains("\"stage\":\"realtime\""))
        assertTrue(api.bodies.single().contains("\"expected_revision\":3"))
    }
    @Test fun unknownManualRequestSurvivesBackgroundAndReplaysSameStage() {
        api.loseSummary = true
        show()
        click(R.string.summary_controls_realtime)
        await(R.string.summary_controls_reconcile)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.progress = api.progress.copy(readyStages = listOf("quick"))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile)
        assertEquals(1, api.keys.size)
        click(R.string.summary_controls_reconcile)
        compose.waitUntil(8000) { api.keys.size == 2 }
        assertEquals(api.keys.first(), api.keys.last())
        assertEquals(api.bodies.first(), api.bodies.last())
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.summary_controls_reconcile)).fetchSemanticsNodes().isEmpty() }
    }
    @Test fun automaticMinutesNeedsExplicitEnableAndStopRemainsAvailableAfterRolloutOff() {
        show(dark = true)
        await(R.string.summary_automation_start)
        assertTrue(api.keys.isEmpty())
        screenshot("automatic-dark")
        click(R.string.summary_automation_start)
        await(R.string.summary_automation_stop)
        assertTrue(api.bodies.first().contains("\"enabled\":true"))
        api.auto = api.auto.copy(available = false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        click(R.string.summary_automation_stop)
        compose.waitUntil(8000) { api.bodies.size == 2 }
        assertTrue(api.bodies.last().contains("\"enabled\":false"))
        assertNotEquals(api.keys.first(), api.keys.last())
    }
    @Test fun unknownAutomationReconcilesOriginalEnableInsteadOfTogglingOff() {
        api.loseAutomation = true
        show()
        click(R.string.summary_automation_start)
        await(R.string.summary_automation_reconcile)
        screenshot("unknown")
        click(R.string.summary_automation_reconcile)
        await(R.string.summary_automation_stop)
        assertEquals(api.keys.first(), api.keys.last())
        assertEquals(api.bodies.first(), api.bodies.last())
        assertEquals(1, api.auto.revision)
    }
    @Test fun generationPermissionRemovalDisposesControlsAndDoesNotSendStoredIntent() {
        val current = mutableStateOf(record)
        compose.setContent { WeMeetTheme { RecordSummaryControls(viewer, current.value, repository) { viewer } } }
        await(R.string.summary_controls_realtime)
        compose.runOnIdle { current.value = record.copy(capabilities = RecordCapabilitiesDto(true, true, false)) }
        compose.onNodeWithText(label(R.string.summary_controls_title)).assertDoesNotExist()
        assertTrue(api.keys.isEmpty())
    }
    @Test fun retryUsesExactFailedJobStageAndAttemptAndReadFailureHidesActions() {
        api.progress = api.progress.copy(readyStages = emptyList(), job = api.job.copy(status = "failed", retryable = true, stage = "quick", attempt = 2))
        show()
        click(R.string.summary_controls_retry)
        await(R.string.summary_job_queued)
        assertTrue(api.bodies.single().contains("\"operation\":\"retry\""))
        assertTrue(api.bodies.single().contains("\"stage\":\"quick\""))
        assertTrue(api.bodies.single().contains("\"expected_attempt\":2"))
        api.failRead = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_read_error)
        compose.onNodeWithText(label(R.string.summary_controls_retry)).assertDoesNotExist()
    }
    @Test fun capturePreviewShowsQuickVersionAndExactSourceThenClearsAfterRevocation() {
        val records = RecordFixture()
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CaptureSummaryWorkspace(viewer, CaptureDto(capture, record.id, "device", "stopping", 4, record.originAt, mediaStatus = "uploading", lastAckedSequence = 1),
                MeetingRecordRepository(records) { viewer }, repository, { viewer }, null)
        } } }
        compose.waitUntil(8000) { compose.onAllNodesWithText("Quick release decision").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.records_quick)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.records_source_at, "0:01")).performScrollTo().performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText("Exact quick draft evidence").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(records.snapshot, records.readSnapshot)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        records.revoked = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_read_error)
        compose.onNodeWithText("Exact quick draft evidence").assertDoesNotExist()
        compose.onNodeWithText("Quick release decision").assertDoesNotExist()
    }
    private class Fixture : MeetingSummaryApi {
        var loseSummary = false
        var loseAutomation = false
        var failRead = false
        val job = SummaryJobDto(UUID.randomUUID().toString(), "queued", 1, 1, "realtime", 3, updatedAt = "2026-09-13T00:00:00Z")
        var progress = SummaryProgressDto(3, null, stagedEnabled = true, readyStages = listOf("realtime"))
        var auto = SummaryAutomationDto(0, false, "off", available = true, canControl = true)
        val keys = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        private val commands = mutableMapOf<String, SummaryAutomationAcceptedDto>()
        override suspend fun progress(record: String): SummaryProgressDto { if (failRead) throw IOException("Synthetic denied read"); return progress }
        override suspend fun automation(record: String) = auto
        override suspend fun request(record: String, key: String, body: RequestBody): SummaryAcceptedDto {
            keys += key
            val json = Buffer().also { body.writeTo(it) }.readUtf8()
            bodies += json
            val stage = if (json.contains("\"stage\":\"quick\"")) "quick" else "realtime"
            progress = progress.copy(job = job.copy(stage = stage), readyStages = emptyList())
            if (loseSummary) { loseSummary = false; throw IOException("Synthetic response lost") }
            return SummaryAcceptedDto(UUID.randomUUID().toString(), false, "pending", progress.job!!)
        }
        override suspend fun control(record: String, key: String, body: RequestBody): SummaryAutomationAcceptedDto {
            keys += key
            val json = Buffer().also { body.writeTo(it) }.readUtf8()
            bodies += json
            commands[key]?.let { return it.copy(replayed = true, current = auto) }
            val enabled = json.contains("\"enabled\":true")
            auto = auto.copy(revision = auto.revision + 1, enabled = enabled, state = if (enabled) "waiting" else "off")
            val result = SummaryAutomationAcceptedDto(UUID.randomUUID().toString(), false, auto, auto)
            commands[key] = result
            if (loseAutomation) { loseAutomation = false; throw IOException("Synthetic response lost") }
            return result
        }
    }
    private inner class RecordFixture : MeetingRecordApi {
        var revoked = false
        val snapshot = UUID.randomUUID().toString()
        val segment = UUID.randomUUID().toString()
        var readSnapshot: String? = null
        override suspend fun record(recordId: String): RecordDto { check(!revoked); return record.copy(capabilities = RecordCapabilitiesDto(true, true, false)) }
        override suspend fun summaries(recordId: String, cursor: String?, versionId: String?) = RecordPageDto(listOf(RecordSummaryVersionDto(UUID.randomUUID().toString(), "quick", snapshot, 3, true,
            record.originAt, "open", asrStatus = "in_progress", content = RecordSummaryContentDto("Quick release decision", listOf(RecordSummaryPointDto("Check the recording", listOf(RecordReferenceDto(segment, 1, 1000, 2000)))), emptyList(), emptyList(), emptyList()))))
        override suspend fun snapshot(recordId: String, snapshotId: String): RecordSnapshotDto { readSnapshot = snapshotId; return RecordSnapshotDto(snapshot, 3, listOf(RecordSnapshotSegmentDto(segment, 1, 1000, 2000, "Exact quick draft evidence"))) }
        override suspend fun records(scope: String, source: String?, hasSummary: Boolean?, query: String?, cursor: String?): RecordPageDto<RecordDto> = error("Unexpected list")
        override suspend fun transcripts(recordId: String, revision: Int, query: String?, cursor: String?): RecordPageDto<RecordOnlineTranscriptDto> = error("Unexpected transcripts")
        override suspend fun originals(recordId: String, revision: Int, query: String?, speakerId: String?, cursor: String?): RecordPageDto<RecordOriginalSegmentDto> = error("Unexpected originals")
        override suspend fun speakers(recordId: String, cursor: String?): RecordPageDto<RecordSpeakerDto> = error("Unexpected speakers")
        override suspend fun resolve(roomId: String, sessionId: String?): RecordDto = error("Unexpected resolve")
    }
}
