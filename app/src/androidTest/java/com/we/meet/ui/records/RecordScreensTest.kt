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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
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
        var renameAllowed = false
        var failRename = false
        var recordTitle = "Private planning meeting"
        val renames = mutableListOf<RecordTitleRequestDto>()
        var revoked = false
        var originals = true
        var stage = "realtime"
        var asr = "in_progress"
        var snapshotReads = 0
        var revision = 3
        val originalQueries = mutableListOf<Pair<String?, String?>>()
        val summarySelectors = mutableListOf<String?>()
        var missingVersion = false
        val queries = mutableListOf<Pair<String, String?>>()
        var searchQuery: String? = null
        var sourceFilter: String? = null
        val summaryFilters = mutableListOf<Boolean?>()
        private fun checkAccess() { check(!revoked) { "Fixture access revoked" } }
        override suspend fun media(recordId: String): RecordMediaDto = error("Media not configured")
        override suspend fun transcriptExport(url: String) = error("Export not configured")
        override suspend fun correctOriginal(recordId: String, segmentId: String, body: com.we.meet.data.api.dto.RecordCorrectionRequest) = error("Correction not configured")
        override suspend fun revertOriginal(recordId: String, segmentId: String) = error("Correction not configured")
        override suspend fun rename(recordId: String, body: RecordTitleRequestDto): RecordDto {
            checkAccess()
            check(renameAllowed && !failRename && body.expectedTitle == recordTitle)
            assertEquals(this@RecordScreensTest.recordId, recordId)
            renames += body
            recordTitle = body.title
            return record(recordId)
        }
        override suspend fun record(recordId: String): RecordDto {
            checkAccess()
            return RecordDto(recordId, "audio_recording", recordTitle, "2026-09-13T00:00:00Z", revision,
                RecordCapabilitiesDto(readSummary = true, readTranscript = originals, rename = renameAllowed), isOngoing = !renameAllowed)
        }
        override suspend fun records(scope: String, source: String?, hasSummary: Boolean?, query: String?, cursor: String?, isOngoing: Boolean?): RecordPageDto<RecordDto> {
            checkAccess()
            // 列表页现在会并行问两次:进行中一段 + 历史一段(见 RecordLibraryScreen)。
            // 这个 fixture 模拟的是「名下没有正在录制的记录」的账号,所以进行中那一段恒空,
            // 分页/空态行为与单列表时完全一致。
            if (isOngoing == true) return RecordPageDto(emptyList())
            queries += scope to cursor
            searchQuery = query
            sourceFilter = source
            summaryFilters += hasSummary
            return if (cursor == null) RecordPageDto(listOf(record(recordId)), "next-page") else RecordPageDto(emptyList())
        }
        override suspend fun summaries(recordId: String, cursor: String?, versionId: String?): RecordPageDto<RecordSummaryVersionDto> {
            checkAccess()
            summarySelectors += versionId
            if (missingVersion && versionId != null) return RecordPageDto(emptyList())
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
        override suspend fun transcripts(recordId: String, revision: Int, query: String?, cursor: String?): RecordPageDto<RecordOnlineTranscriptDto> = error("Wrong source endpoint")
        override suspend fun originals(recordId: String, revision: Int, query: String?, speakerId: String?, cursor: String?): RecordPageDto<RecordOriginalSegmentDto> {
            checkAccess()
            originalQueries += query to speakerId
            return RecordPageDto(listOf(RecordOriginalSegmentDto(segmentId, 1, snapshotId, versionId, "Speaker 1", 1000, 3000,
                if (query == null) "Full original text" else "Search matched original")))
        }
        override suspend fun speakers(recordId: String, cursor: String?): RecordPageDto<RecordSpeakerDto> =
            RecordPageDto(listOf(RecordSpeakerDto(versionId, "Speaker 1", "diarized")))
        override suspend fun resolve(roomId: String, sessionId: String?): RecordDto = error("No room fallback")
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun ownerCanRenameAndBlankNameCannotBeSaved() {
        val fixture = Fixture().apply { renameAllowed = true }
        val repository = MeetingRecordRepository(fixture) { "owner" }
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository, "owner", recordId, {}) } }
        awaitText(label(R.string.record_rename))
        compose.onNodeWithText(label(R.string.record_rename)).performClick()
        compose.onNodeWithText(label(R.string.record_name)).performTextReplacement("  ")
        compose.onNodeWithText(label(R.string.record_rename_save)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.record_name)).performTextReplacement("Design review")
        compose.waitForIdle()
        Thread.sleep(300)
        File(context.getExternalFilesDir(null), "record-rename-dialog.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(label(R.string.record_rename_save)).performClick()
        awaitText("Design review")
        assertEquals("Design review", fixture.recordTitle)
        assertEquals("Private planning meeting", fixture.renames.single().expectedTitle)
    }

    @Test fun failedRenameKeepsOriginalNameAndDraftForRetry() {
        val fixture = Fixture().apply { renameAllowed = true; failRename = true }
        val repository = MeetingRecordRepository(fixture) { "owner" }
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository, "owner", recordId, {}) } }
        awaitText(label(R.string.record_rename))
        compose.onNodeWithText(label(R.string.record_rename)).performClick()
        compose.onNodeWithText(label(R.string.record_name)).performTextReplacement("Draft name")
        compose.onNodeWithText(label(R.string.record_rename_save)).performClick()
        awaitText(label(R.string.record_rename_error))
        compose.onNodeWithText("Draft name").assertIsDisplayed()
        assertEquals("Private planning meeting", fixture.recordTitle)
        assertTrue(fixture.renames.isEmpty())
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
        awaitText(label(R.string.records_minutes))
        compose.onNodeWithText(label(R.string.records_minutes)).performClick()
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

    @Test fun librarySearchAndFiltersDriveTheQuery() {
        val fixture = Fixture()
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", false, {}, {}) } }
        awaitText("Private planning meeting")
        compose.onNodeWithContentDescription(label(R.string.records_search)).performClick()
        compose.onNodeWithText(label(R.string.records_search)).performTextInput("Design")
        compose.onNodeWithText(label(R.string.records_search_action)).performClick()
        compose.waitUntil(5_000) { fixture.searchQuery == "Design" }
        compose.onNodeWithContentDescription(label(R.string.records_clear_search)).performClick()
        compose.waitUntil(5_000) { fixture.searchQuery == null }
        compose.onNodeWithContentDescription(label(R.string.records_filters)).performClick()
        compose.onNodeWithText(label(R.string.records_uploaded)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.records_filters_done)).performClick()
        compose.waitUntil(5_000) { fixture.sourceFilter == "upload" }
        compose.onNodeWithText(label(R.string.records_reset_filters)).performClick()
        compose.waitUntil(5_000) { fixture.sourceFilter == null }
        // 这一页不再挂「录音/导入」常驻底栏:两个动作归属 AI 录音页,列表页只查只看。
        compose.onNodeWithText(label(R.string.records_start_recording)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_upload)).assertDoesNotExist()
    }

    @Test fun minutesLibraryUsesOwnershipTabsAndOpensSummaryReader() {
        val fixture = Fixture()
        var opened: String? = null
        compose.setContent { WeMeetTheme(darkTheme = false) {
            RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", true,
                onRecord = { error("Minutes must open the summary reader") }, onBack = {}, onSummaryRecord = { opened = it })
        } }
        awaitText("Private planning meeting")
        assertEquals("owned" to null, fixture.queries.last())
        compose.onNodeWithText(label(R.string.minutes_participated)).performClick()
        compose.waitUntil(5_000) { fixture.queries.lastOrNull() == ("participated" to null) }
        compose.onNodeWithText(label(R.string.minutes_shared)).performClick()
        compose.waitUntil(5_000) { fixture.queries.lastOrNull() == ("shared" to null) }
        assertTrue(fixture.summaryFilters.all { it == true })
        screenshot("minutes-library-light")
        compose.onNodeWithText("Private planning meeting").performClick()
        assertEquals(recordId, opened)
    }

    @Test fun summaryEntryShowsOverviewAndCollapsibleDecisionsWithoutTranscriptReads() {
        val fixture = Fixture().apply { stage = "final"; asr = "finished" }
        compose.setContent { WeMeetTheme(darkTheme = false) {
            RecordDetailScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", recordId, {}, initialSummary = true)
        } }
        awaitText("Confirm release scope")
        assertTrue(fixture.originalQueries.isEmpty())
        assertTrue(fixture.summarySelectors.all { it == null })
        compose.onNodeWithText("${label(R.string.records_decisions)} · 1").performScrollTo().performClick()
        compose.onNodeWithText("Review capture recovery").assertDoesNotExist()
        compose.onNodeWithText("${label(R.string.records_decisions)} · 1").performClick()
        compose.onNodeWithText("Review capture recovery").assertIsDisplayed()
        screenshot("minutes-reader-light")
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

    @Test fun originalSearchAndSpeakerSelectionUseServerFilters() {
        val fixture = Fixture()
        detail(fixture)
        compose.onNodeWithText(label(R.string.records_originals)).performClick()
        awaitText("Full original text")
        compose.onNodeWithText(label(R.string.records_search_originals)).performClick()
        compose.onNodeWithText(label(R.string.records_search_originals)).performTextInput("release 中文")
        compose.onNodeWithText(label(R.string.records_search_action)).performClick()
        awaitText("Search matched original")
        compose.onNodeWithText(label(R.string.records_filter_speaker)).performClick()
        awaitText(label(R.string.records_all_speakers))
        compose.onAllNodesWithText("Speaker 1")[1].performClick()
        compose.waitUntil(5_000) { fixture.originalQueries.lastOrNull() == ("release 中文" to versionId) }
        compose.onNodeWithText(label(R.string.records_clear_filters)).performClick()
        awaitText("Full original text")
        compose.onNodeWithText(label(R.string.records_clear_search)).performClick()
        screenshot("records-originals-light")
    }

    @Test fun missingLinkedVersionDoesNotSilentlyOpenLatest() {
        val fixture = Fixture().apply { missingVersion = true }
        val repo = MeetingRecordRepository(fixture) { "reader" }
        compose.setContent {
            WeMeetTheme(darkTheme = false) { RecordDetailScreen(repo, "reader", recordId, {}, summaryVersionId = versionId) }
        }
        awaitText(label(R.string.records_linked_version_unavailable))
        assertTrue(fixture.summarySelectors.isNotEmpty())
        assertTrue(fixture.summarySelectors.all { it == versionId })
        compose.onNodeWithText("Confirm release scope").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_all_versions)).performClick()
        awaitText("Confirm release scope")
        assertNull(fixture.summarySelectors.last())
    }
}
