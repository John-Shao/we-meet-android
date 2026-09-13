package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.capture.MeetingIntentStore
import com.we.meet.data.repository.CaptureTranscriptionRepository
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
class CaptureAsrPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "asr-ui-${UUID.randomUUID()}"
    private val capture = CaptureDto(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "device", "recording", 2,
        "2026-09-13T00:00:00Z", mediaStatus = "uploading", lastAckedSequence = 1)
    private val api = Fixture()
    private val repository = CaptureTranscriptionRepository(api) { viewer }
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show() { compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
        CaptureAsrPanel(viewer, capture, repository) { viewer }
    } } } }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "capture-asr-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun openingDoesNotStartPaidJobAndExplicitStartSendsRequiredNull() {
        show()
        await(R.string.capture_asr_start_live)
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.capture_asr_start_live)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        assertTrue(api.keys.isEmpty())
        screenshot("ready")
        compose.onNodeWithText(label(R.string.capture_asr_start_live)).performScrollTo().performClick()
        await(R.string.capture_asr_cancel)
        assertEquals(1, api.keys.size)
        assertTrue(api.bodies.single().contains("\"expected_job_id\":null"))
        assertTrue(api.bodies.single().contains("\"allow_incomplete\":false"))
        assertTrue(api.bodies.single().contains("\"live\":true"))
        compose.onNodeWithText(label(R.string.capture_asr_cancel)).performScrollTo().performClick()
        await(R.string.capture_asr_canceled)
        assertEquals(listOf(api.job.id), api.canceled)
    }

    @Test fun lostResponseRequiresExplicitReconciliationWithOriginalKeyAndBody() {
        api.loseResponse = true
        show()
        await(R.string.capture_asr_start_live)
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.capture_asr_start_live)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText(label(R.string.capture_asr_start_live)).performScrollTo().performClick()
        await(R.string.capture_asr_reconcile)
        val frozen = MeetingIntentStore.open(context, viewer) { viewer }.use { it.get(MeetingIntentKind.CAPTURE_ASR, capture.id)!! }
        screenshot("unknown")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.capture_asr_reconcile)
        assertEquals(1, api.keys.size)
        compose.onNodeWithText(label(R.string.capture_asr_reconcile)).performScrollTo().performClick()
        await(R.string.capture_asr_cancel)
        assertEquals(listOf(frozen.key, frozen.key), api.keys.toList())
        assertEquals(api.bodies.first(), api.bodies.last())
    }

    @Test fun existingPendingRequestCanBeReconciledWhenNewCreationIsDisabled() {
        val frozen = MeetingIntentStore.open(context, viewer) { viewer }.use {
            it.getOrCreate(MeetingIntentKind.CAPTURE_ASR, capture.id, "{\"expected_job_id\":null,\"allow_incomplete\":false,\"live\":true}")
        }
        api.enabled = false
        show()
        await(R.string.capture_asr_reconcile)
        assertTrue(api.keys.isEmpty())
        compose.onNodeWithText(label(R.string.capture_asr_reconcile)).performScrollTo().performClick()
        await(R.string.capture_asr_cancel)
        assertEquals(listOf(frozen.key), api.keys.toList())
    }

    @Test fun previewUsesExactJobAndOlderPagesAndHidesTextAfterReadFailure() {
        api.active = true
        api.job = api.job.copy(status = "running", finalCount = 51)
        show()
        compose.waitUntil(8000) { compose.onAllNodesWithText("Private confirmed segment 2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, api.cursors.first())
        screenshot("preview")
        compose.onNodeWithText(label(R.string.capture_asr_previous)).performScrollTo().performClick()
        compose.waitUntil(8000) { api.cursors.contains(0) }
        compose.onNodeWithText(label(R.string.capture_asr_latest)).performScrollTo().performClick()
        api.failPreview = true
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.capture_asr_read_error)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Private confirmed segment 2").assertDoesNotExist()
        assertEquals(setOf(api.job.id), api.previewJobs.toSet())
    }

    @Test fun backgroundStopsReadsAndRestoringScreenFetchesBeforeShowingPrivateText() {
        api.active = true
        api.job = api.job.copy(status = "running", finalCount = 1)
        show()
        compose.waitUntil(8000) { api.cursors.isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val reads = api.cursors.size
        // Wait across one actual preview interval while the activity is not resumed.
        Thread.sleep(3300)
        assertEquals(reads, api.cursors.size)
        api.failPreview = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.capture_asr_read_error)
        compose.onNodeWithText("Private confirmed segment 1").assertDoesNotExist()
        assertTrue(api.keys.isEmpty())
    }

    private class Fixture : CaptureTranscriptionApi {
        @Volatile var enabled = true
        @Volatile var active = false
        @Volatile var loseResponse = false
        @Volatile var failPreview = false
        @Volatile var job = CaptureAsrJobDto(UUID.randomUUID().toString(), 1, "queued", 1, 0, 0, "uploading", "live", false)
        val keys = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val canceled = CopyOnWriteArrayList<String>()
        val cursors = CopyOnWriteArrayList<Int>()
        val previewJobs = CopyOnWriteArrayList<String>()
        override suspend fun state(capture: String) = CaptureAsrStateDto(enabled, enabled, results = if (active) listOf(job) else emptyList())
        override suspend fun request(capture: String, key: String, request: RequestBody): CaptureAsrCreatedDto {
            keys += key
            bodies += Buffer().also { request.writeTo(it) }.readUtf8()
            active = true
            if (loseResponse) { loseResponse = false; throw IOException("Synthetic unknown outcome") }
            return CaptureAsrCreatedDto(job, keys.size == 1)
        }
        override suspend fun cancel(capture: String, job: String, empty: RequestBody): CaptureAsrJobDto {
            canceled += job
            this.job = this.job.copy(status = "canceled")
            return this.job
        }
        override suspend fun preview(capture: String, job: String, after: Int): CaptureAsrPreviewDto {
            cursors += after
            previewJobs += job
            if (failPreview) throw IOException("Synthetic revoked access")
            val count = this.job.finalCount
            val rows = if (count > after) listOf(CaptureAsrPreviewRowDto(UUID.randomUUID().toString(), after + 1, after * 1000L,
                (after + 1) * 1000L, "Private confirmed segment ${after + 1}", "en")) else emptyList()
            return CaptureAsrPreviewDto(job, this.job.status, count, false, null, rows)
        }
    }
}
