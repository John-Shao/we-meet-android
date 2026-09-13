package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordScreensTest {
    @get:Rule val compose = createComposeRule()
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val snapshotId = "22222222-2222-4222-8222-222222222222"
    private val segmentId = "33333333-3333-4333-8333-333333333333"
    private val versionId = "44444444-4444-4444-8444-444444444444"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private inner class Fixture : MeetingRecordApi {
        var revoked = false
        var originals = true
        var stage = "realtime"
        var asr = "in_progress"
        var snapshotReads = 0
        val queries = mutableListOf<Pair<String, String?>>()
        private fun checkAccess() { check(!revoked) { "Fixture access revoked" } }
        override suspend fun record(recordId: String): RecordDto {
            checkAccess()
            return RecordDto(recordId, "audio_recording", "Private planning meeting", "2026-09-13T00:00:00Z", 3,
                RecordCapabilitiesDto(readSummary = true, readTranscript = originals), isOngoing = true)
        }
        override suspend fun records(scope: String, source: String?, hasSummary: Boolean?, query: String?, cursor: String?): RecordPageDto<RecordDto> {
            checkAccess()
            queries += scope to cursor
            return if (cursor == null) RecordPageDto(listOf(record(recordId)), "next-page") else RecordPageDto(emptyList())
        }
        override suspend fun summaries(recordId: String, cursor: String?, versionId: String?): RecordPageDto<RecordSummaryVersionDto> {
            checkAccess()
            return RecordPageDto(listOf(RecordSummaryVersionDto(this@RecordScreensTest.versionId, stage, snapshotId, 3, true,
                "2026-09-13T01:00:00Z", "open", asrStatus = asr,
                content = RecordSummaryContentDto("Confirm release scope", listOf(RecordSummaryPointDto("Review capture recovery",
                    listOf(RecordReferenceDto(segmentId, 2, 1000, 3000)))), emptyList(), emptyList(), emptyList()))))
        }
        override suspend fun snapshot(recordId: String, snapshotId: String): RecordSnapshotDto {
            checkAccess()
            snapshotReads++
            return RecordSnapshotDto(snapshotId, 3, listOf(RecordSnapshotSegmentDto(segmentId, 2, 1000, 3000, "Exact recorded evidence")))
        }
        override suspend fun resolve(roomId: String, sessionId: String?): RecordDto = error("No room fallback")
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.externalCacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun detail(fixture: Fixture, dark: Boolean = false): Owner {
        val owner = Owner()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        val repo = MeetingRecordRepository(fixture) { "reader" }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WeMeetTheme(darkTheme = dark) { RecordDetailScreen(repo, "reader", recordId, {}) }
            }
        }
        awaitText("Confirm release scope")
        return owner
    }

    @Test fun paginationEmptyStateCanReturnAndFiltersResetCursor() {
        val fixture = Fixture()
        val repo = MeetingRecordRepository(fixture) { "reader" }
        compose.setContent { WeMeetTheme(darkTheme = false) { RecordLibraryScreen(repo, "reader", false, {}, {}) } }
        awaitText("Private planning meeting")
        screenshot("records-library-light")
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText(label(R.string.records_empty))
        compose.onNodeWithText(label(R.string.records_refresh)).performClick()
        awaitText("Private planning meeting")
        compose.onNodeWithText(label(R.string.records_shared)).performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.queries.lastOrNull() == ("shared" to null) }
        assertTrue(fixture.queries.contains("recent" to "next-page"))
    }

    @Test fun backgroundClearsPrivateBodyAndRevocationFailsClosedOnReturn() {
        val fixture = Fixture()
        val owner = detail(fixture)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Confirm release scope").fetchSemanticsNodes().isEmpty() }
        fixture.revoked = true
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        awaitText(label(R.string.records_unavailable))
        compose.onNodeWithText("Confirm release scope").assertDoesNotExist()
    }

    @Test fun exactCitationOpensAndDoesNotSurviveRevocation() {
        val fixture = Fixture()
        val owner = detail(fixture)
        compose.onNodeWithText(context.getString(R.string.records_source_at, "0:01")).performScrollTo().performClick()
        awaitText("Exact recorded evidence")
        assertEquals(1, fixture.snapshotReads)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        fixture.revoked = true
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        awaitText(label(R.string.records_unavailable))
        compose.onNodeWithText("Exact recorded evidence").assertDoesNotExist()
    }

    @Test fun summaryOnlyAccessHasNoSourceControl() {
        val fixture = Fixture().apply { originals = false }
        detail(fixture)
        compose.onNodeWithText(context.getString(R.string.records_source_at, "0:01")).assertDoesNotExist()
        assertEquals(0, fixture.snapshotReads)
        screenshot("records-summary-only-light")
    }

    @Test fun unknownStageIsNotMislabelledFinalAndIncompleteIsExplicit() {
        val fixture = Fixture().apply { stage = "future-stage"; asr = "incomplete" }
        detail(fixture, dark = true)
        compose.onNodeWithText(label(R.string.records_final)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_incomplete)).assertIsDisplayed()
        screenshot("records-summary-dark")
    }
}
