package com.we.meet.ui.records

import android.graphics.Bitmap
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import okhttp3.ResponseBody.Companion.toResponseBody
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
import com.we.meet.data.repository.RecordSourceChangedException
import kotlinx.coroutines.CompletableDeferred
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
        var sourceType = "audio_recording"
        var uploadCanControl = false
        val renames = mutableListOf<RecordTitleRequestDto>()
        var revoked = false
        var originals = true
        var stage = "realtime"
        var asr = "in_progress"
        var snapshotReads = 0
        var revision = 3
        var correctionVersion = 2
        var canCorrect = false
        var correctionGate: CompletableDeferred<Unit>? = null
        val corrections = mutableListOf<RecordCorrectionRequest>()
        val originalQueries = mutableListOf<Pair<String?, String?>>()
        val summarySelectors = mutableListOf<String?>()
        var missingVersion = false
        var chapters = false
        var noVersions = false
        val queries = mutableListOf<Pair<String, String?>>()
        var searchQuery: String? = null
        var sourceFilter: String? = null
        val summaryFilters = mutableListOf<Boolean?>()
        private fun checkAccess() { check(!revoked) { "Fixture access revoked" } }
        override suspend fun media(recordId: String, download: Boolean?): RecordMediaDto = error("Media not configured")
        var exportReads = 0
        override suspend fun transcriptExport(url: String): okhttp3.ResponseBody {
            checkAccess()
            exportReads++
            return "Exported original text 中文".toResponseBody()
        }
        override suspend fun correctOriginal(recordId: String, segmentId: String, body: RecordCorrectionRequest): RecordCorrectionDto {
            checkAccess()
            corrections += body
            correctionGate?.await()
            throw RecordSourceChangedException()
        }
        override suspend fun revertOriginal(recordId: String, segmentId: String, expectedRevision: Int) = error("Correction not configured")
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
            return RecordDto(recordId, sourceType, recordTitle, "2026-09-13T00:00:00Z", revision,
                RecordCapabilitiesDto(readSummary = true, readTranscript = originals, rename = renameAllowed), isOngoing = !renameAllowed,
                upload = if (sourceType == "upload") RecordUploadDto(canControl = uploadCanControl) else null)
        }
        val dateQueries = java.util.concurrent.CopyOnWriteArrayList<List<String?>>()
        val orderQueries = java.util.concurrent.CopyOnWriteArrayList<List<String?>>()
        var supportsDates = true
        override suspend fun filteredRecords(scope: String, source: String?, hasSummary: Boolean?, query: String?, cursor: String?, isOngoing: Boolean?, createdFrom: String?, createdBefore: String?, ordering: String?): RecordPageDto<RecordDto> {
            if (createdFrom != null || createdBefore != null) dateQueries += listOf(createdFrom, createdBefore, cursor, source)
            orderQueries += listOf(ordering, cursor, source, query, isOngoing?.toString(), hasSummary?.toString())
            return records(scope, source, hasSummary, query, cursor, isOngoing).copy(
                supportedFilters = if (supportsDates) listOf("created_from", "created_before") else emptyList())
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
            if (noVersions) return RecordPageDto(emptyList())
            if (missingVersion && versionId != null) return RecordPageDto(emptyList())
            return RecordPageDto(listOf(RecordSummaryVersionDto(this@RecordScreensTest.versionId, stage, snapshotId, 3, true,
                "2026-09-13T01:00:00Z", "open", asrStatus = asr,
                content = RecordSummaryContentDto("Confirm release scope", listOf(RecordSummaryPointDto("Review capture recovery",
                    listOf(RecordReferenceDto(segmentId, 2, 1000, 3000)))),
                    if (chapters) listOf(RecordSummaryPointDto("Chapter one", listOf(RecordReferenceDto(segmentId, 2, 1000, 3000)))) else emptyList(),
                    emptyList(), emptyList()))))
        }
        override suspend fun snapshot(recordId: String, snapshotId: String): RecordSnapshotDto {
            checkAccess()
            snapshotReads++
            return RecordSnapshotDto(snapshotId, 3, listOf(RecordSnapshotSegmentDto(segmentId, 2, 1000, 3000, "Exact recorded evidence")))
        }
        override suspend fun transcripts(recordId: String, revision: Int, query: String?, cursor: String?): RecordPageDto<RecordOnlineTranscriptDto> = error("Wrong source endpoint")
        override suspend fun originals(recordId: String, revision: Int, query: String?, speakerId: String?, cursor: String?, atMs: Long?): RecordPageDto<RecordOriginalSegmentDto> {
            checkAccess()
            originalQueries += query to speakerId
            return RecordPageDto(listOf(RecordOriginalSegmentDto(segmentId, 1, snapshotId, versionId, "Speaker 1", 1000, 3000,
                if (query == null) "Full original text" else "Search matched original", correctionRevision = correctionVersion, canCorrect = canCorrect)))
        }
        override suspend fun speakers(recordId: String, cursor: String?): RecordPageDto<RecordSpeakerDto> =
            RecordPageDto(listOf(RecordSpeakerDto(versionId, "Speaker 1", "diarized")))

        override suspend fun attributeSpeaker(
            recordId: String,
            speakerId: String,
            body: com.we.meet.data.api.dto.RecordAttributionRequest,
        ): RecordSpeakerDto = error("Speaker attribution not configured")

        override suspend fun attributionCandidates(
            recordId: String,
            query: String?,
        ): com.we.meet.data.api.dto.RecordAttributionCandidatePageDto =
            com.we.meet.data.api.dto.RecordAttributionCandidatePageDto()

        override suspend fun resolve(roomId: String, sessionId: String?): RecordDto = error("No room fallback")
    }
/** 「排序 / 筛选 / 回收站」收在顶栏的溢出菜单里(顶栏只留三格,设计规范 §3)。 */
    private fun openMore() {
        compose.onNodeWithContentDescription(label(R.string.cd_records_more)).performClick()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sharedUploadDoesNotRequestOwnerOnlyTranscriptionStatus() = checkUploadStatusAccess(false)

    @Test fun uploadOwnerStillSeesTranscriptionFailureStatus() = checkUploadStatusAccess(true)

    private fun checkUploadStatusAccess(canControl: Boolean) {
        val app = context.applicationContext as com.we.meet.WeMeetApp
        val originalRepository = app.recordingUploadRepository
        val repositoryField = com.we.meet.WeMeetApp::class.java.getDeclaredField("recordingUploadRepository").apply {
            isAccessible = true
        }
        val statusReads = java.util.concurrent.atomic.AtomicInteger()
        val api = java.lang.reflect.Proxy.newProxyInstance(
            com.we.meet.data.api.RecordingUploadApi::class.java.classLoader,
            arrayOf(com.we.meet.data.api.RecordingUploadApi::class.java),
        ) { _, method, _ ->
            check(method.name == "state") { "Unexpected upload call: ${method.name}" }
            statusReads.incrementAndGet()
            com.we.meet.data.api.RecordingUploadState(recordId, "failed", 1)
        } as com.we.meet.data.api.RecordingUploadApi
        try {
            repositoryField.set(app, com.we.meet.data.repository.RecordingUploadRepository(api, { "reader" }))
            detail(Fixture().apply { sourceType = "upload"; uploadCanControl = canControl })
            if (canControl) {
                awaitText(label(R.string.record_upload_failed))
                assertTrue(statusReads.get() > 0)
            } else {
                awaitText("Private planning meeting")
                compose.waitForIdle()
                assertEquals(0, statusReads.get())
                compose.onNodeWithText(label(R.string.records_unavailable)).assertDoesNotExist()
            }
        } finally { repositoryField.set(app, originalRepository) }
    }

    @Test fun transcriptExportSurvivesTheSystemPickerBackgroundRoundTrip() {
        val fixture = Fixture()
        val owner = Owner()
        var requestCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val repository = MeetingRecordRepository(fixture) { "reader" }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "miaoji-export-lifecycle-test.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf()) }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registryOwner) {
                    WeMeetTheme { RecordDetailScreen(repository, "reader", recordId, {}) }
                }
            }
            awaitText("Full original text")
            clickTranscriptAction(R.string.records_export_transcript)
            compose.onNodeWithText("TXT").performClick()
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Full original text").fetchSemanticsNodes().isEmpty() }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            awaitText("Full original text")
            compose.runOnUiThread { registry.dispatchResult(requireNotNull(requestCode), Activity.RESULT_OK, Intent().setData(uri)) }
            compose.waitUntil(5_000) {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } == "Exported original text 中文"
            }
            assertEquals(1, fixture.exportReads)
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun chapterTabOpensItsImmutableEvidence() {
        val fixture = Fixture().apply { chapters = true }
        detail(fixture)
        compose.onNodeWithText(label(R.string.records_chapters)).performClick()
        awaitText("Chapter one")
        compose.onNodeWithText("Confirm release scope").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.records_source_at, "0:01")).performScrollTo().performClick()
        awaitText("Exact recorded evidence")
        assertEquals(1, fixture.snapshotReads)
    }

    @Test fun speakerActivityExplainsPartialRecognizedTime() {
        compose.setContent { WeMeetTheme { SpeakerActivity(RecordSpeakerActivityDto("recognized_speaker_time", "partial", 65000, 42.5)) } }
        compose.onNodeWithText(context.getString(R.string.records_activity_value, "1:05", 42.5)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.records_activity_partial)).assertIsDisplayed()
    }

    @Test fun speakerActivityDoesNotInventMissingStatistics() {
        compose.setContent { WeMeetTheme { SpeakerActivity(null) } }
        compose.onNodeWithText(label(R.string.records_activity_unavailable)).assertIsDisplayed()
    }

    @Test fun correctionDraftSurvivesARecordRevisionReload() {
        val fixture = Fixture().apply { canCorrect = true }
        val repository = MeetingRecordRepository(fixture) { "reader" }
        val record = mutableStateOf(RecordDto(recordId, "audio_recording", "Draft scope", "2026-09-13T00:00:00Z", 3,
            RecordCapabilitiesDto(readTranscript = true)))
        compose.setContent { WeMeetTheme { RecordOriginals(repository, "reader", record.value, {}, onExport = {}) } }
        awaitText(label(R.string.records_correction_edit))
        compose.onNodeWithText(label(R.string.records_correction_edit)).performClick()
        compose.onNodeWithText("Full original text").performTextReplacement("My unsaved draft")
        compose.runOnIdle { fixture.revision = 4; fixture.correctionVersion = 3; record.value = record.value.copy(revision = 4) }
        awaitText("My unsaved draft")
        compose.onNodeWithText(label(R.string.records_correction_save)).performClick()
        awaitText(label(R.string.records_correction_conflict))
        compose.onNodeWithText("My unsaved draft").assertIsDisplayed()
        assertEquals(2, fixture.corrections.single().expectedRevision)
    }

    @Test fun pendingCorrectionSurvivesPageDisposalAndCannotBeSentTwice() {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture().apply { canCorrect = true; correctionGate = gate }
        val repository = MeetingRecordRepository(fixture) { "reader" }
        val record = mutableStateOf(RecordDto(recordId, "audio_recording", "Draft scope", "2026-09-13T00:00:00Z", 3,
            RecordCapabilitiesDto(readTranscript = true)))
        compose.setContent { WeMeetTheme { RecordOriginals(repository, "reader", record.value, {}, onExport = {}) } }
        awaitText(label(R.string.records_correction_edit))
        compose.onNodeWithText(label(R.string.records_correction_edit)).performClick()
        compose.onNodeWithText("Full original text").performTextReplacement("Pending draft")
        compose.onNodeWithText(label(R.string.records_correction_save)).performClick()
        compose.waitUntil(5_000) { fixture.corrections.size == 1 }
        compose.runOnIdle { fixture.revision = 4; record.value = record.value.copy(revision = 4) }
        awaitText("Pending draft")
        compose.onNodeWithText(label(R.string.records_correction_saving)).assertIsNotEnabled()
        gate.complete(Unit)
        awaitText(label(R.string.records_correction_conflict))
        compose.onNodeWithText("Pending draft").assertIsDisplayed()
        assertEquals(1, fixture.corrections.size)
    }

    @Test fun summaryOnlyChaptersDoNotReadOriginals() {
        val fixture = Fixture().apply { chapters = true; originals = false }
        detail(fixture)
        compose.onNodeWithText(label(R.string.records_chapters)).performClick()
        awaitText("Chapter one")
        compose.onNodeWithText(context.getString(R.string.records_source_at, "0:01")).assertDoesNotExist()
        assertEquals(0, fixture.snapshotReads)
    }

    @Test fun chapterEmptyStateDoesNotInventAShortRecordingReason() {
        val fixture = Fixture()
        detail(fixture)
        compose.onNodeWithText(label(R.string.records_chapters)).performClick()
        awaitText(label(R.string.records_chapters_empty))
        compose.onNodeWithText(label(R.string.records_chapters_empty)).assertIsDisplayed()
        fixture.noVersions = true
        compose.onNodeWithText(label(R.string.records_refresh)).performClick()
        awaitText(label(R.string.records_chapters_no_version))
        compose.onNodeWithText(label(R.string.records_chapters_empty)).assertDoesNotExist()
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

    @Test fun uploadedRecordUsesTheGuardedRenameAction() {
        val fixture = Fixture().apply { renameAllowed = true; sourceType = "upload" }
        val repository = MeetingRecordRepository(fixture) { "owner" }
        compose.setContent { WeMeetTheme { RecordDetailScreen(repository, "owner", recordId, {}) } }
        awaitText(label(R.string.record_rename))
        compose.onNodeWithText(label(R.string.record_rename)).performClick()
        compose.onNodeWithText(label(R.string.record_name)).performTextReplacement("Uploaded interview")
        compose.onNodeWithText(label(R.string.record_rename_save)).performClick()
        awaitText("Uploaded interview")
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

    /**
     * 逐字稿的动作(导出 / 按发言人筛选 / 批量查找替换 / 清除筛选)现在收在一个
     * 溢出菜单里 —— 原先四个文字按钮在 FlowRow 里换行,"批量查找替换" 单独掉到
     * 第二行,一条只有一颗按钮的工具栏白占屏高。所以点这些动作之前先开菜单。
     */
    private fun openTranscriptActions() {
        compose.onNodeWithContentDescription(label(R.string.records_transcript_actions)).performClick()
    }

    private fun clickTranscriptAction(id: Int) {
        openTranscriptActions()
        compose.onNodeWithText(label(id)).performClick()
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
        compose.onNodeWithContentDescription(label(R.string.cd_records_search)).performClick()
        compose.onNodeWithText(label(R.string.records_search)).performTextInput("Design")
        compose.onNodeWithText(label(R.string.records_search_action)).performClick()
        compose.waitUntil(5_000) { fixture.searchQuery == "Design" }
        compose.onNodeWithContentDescription(label(R.string.cd_records_clear_search)).performClick()
        compose.waitUntil(5_000) { fixture.searchQuery == null }
        openMore()
        compose.onNodeWithText(label(R.string.records_filters)).performClick()
        compose.onNodeWithText(label(R.string.records_uploaded)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.records_filters_done)).performClick()
        compose.waitUntil(5_000) { fixture.sourceFilter == "upload" }
        compose.onNodeWithText(label(R.string.records_reset_filters)).performClick()
        compose.waitUntil(5_000) { fixture.sourceFilter == null }
        // 这一页不再挂「录音/导入」常驻底栏:两个动作归属 AI 录音页,列表页只查只看。
        compose.onNodeWithText(label(R.string.records_start_recording)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_upload)).assertDoesNotExist()
    }

    @Test fun datesCombineWithSourceResetCursorAndRejectInvalidRange() {
        val fixture = Fixture()
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", false, {}, {}) } }
        awaitText("Private planning meeting")
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText(label(R.string.records_empty))
        openMore()
        compose.onNodeWithText(label(R.string.records_filters)).performClick()
        compose.onNodeWithText(label(R.string.records_uploaded)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.records_created_from)).performScrollTo().performTextInput("2026-09-21")
        compose.onNodeWithText(label(R.string.records_created_through)).performScrollTo().performTextInput("2026-09-20")
        compose.onNodeWithText(label(R.string.records_filters_done)).performScrollTo().performClick()
        awaitText(label(R.string.records_date_error))
        compose.onNodeWithText(label(R.string.records_date_error)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.records_filters_done)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.records_created_through)).assertIsNotFocused()
        // Capture both windows: the modal sheet and the activity have separate roots.
        // IME animations run on real time, independently of Compose's test clock.
        android.os.SystemClock.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.externalCacheDir, "records-invalid-date.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(fixture.dateQueries.isEmpty())
        compose.onNodeWithText(label(R.string.records_created_from)).performScrollTo().performTextReplacement("2026-09-20")
        compose.onNodeWithText(label(R.string.records_filters_done)).performScrollTo().performClick()
        compose.waitUntil(8000) { fixture.dateQueries.size >= 2 }
        val dates = recordDateRange("2026-09-20", "2026-09-20")
        assertTrue(fixture.dateQueries.all { it == listOf(dates.first, dates.second, null, "upload") })
        awaitText("Private planning meeting")
        compose.onNodeWithText(label(R.string.records_reset_filters)).performClick()
        compose.waitUntil(8000) { fixture.sourceFilter == null }
    }

    @Test fun orderingResetsPaginationAndPreservesSourceAndDateFilters() {
        val fixture = Fixture()
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", false, {}, {}) } }
        awaitText("Private planning meeting")
        openMore()
        compose.onNodeWithText(label(R.string.records_filters)).performClick()
        compose.onNodeWithText(label(R.string.records_uploaded)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.records_created_from)).performScrollTo().performTextInput("2026-09-20")
        compose.onNodeWithText(label(R.string.records_filters_done)).performScrollTo().performClick()
        awaitText("Private planning meeting")
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText(label(R.string.records_empty))
        fixture.orderQueries.clear()
        fixture.dateQueries.clear()
        openMore()
        compose.onNodeWithText(label(R.string.records_oldest)).performClick()
        awaitText("Private planning meeting")
        compose.waitUntil(5000) { fixture.orderQueries.size >= 2 }
        assertTrue(fixture.orderQueries.all { it[0] == "created_at" && it[1] == null && it[2] == "upload" })
        assertEquals(setOf("true", "false"), fixture.orderQueries.map { it[4] }.toSet())
        assertTrue(fixture.dateQueries.all { it[0] == recordDateRange("2026-09-20", "").first })
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText(label(R.string.records_empty))
        assertTrue(fixture.orderQueries.any { it[0] == "created_at" && it[1] == "next-page" })
        openMore()
        compose.onNodeWithText(label(R.string.records_newest)).performClick()
        awaitText("Private planning meeting")
        assertTrue(fixture.orderQueries.any { it[0] == "-created_at" && it[1] == null })
    }

    @Test fun minutesLibraryOrderingKeepsSummaryScope() {
        val fixture = Fixture()
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", true, {}, {}) } }
        awaitText("Private planning meeting")
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText(label(R.string.minutes_empty))
        fixture.orderQueries.clear()
        openMore()
        compose.onNodeWithText(label(R.string.records_oldest)).performClick()
        awaitText("Private planning meeting")
        assertEquals(listOf("created_at", null, null, null, null, "true"), fixture.orderQueries.single())
        assertEquals("owned" to null, fixture.queries.last())
        screenshot("records-ordered-minutes")
    }

    @Test fun oldServerDateResponseCannotDisplayUnfilteredRecords() {
        val fixture = Fixture().apply { supportsDates = false }
        compose.setContent { WeMeetTheme { RecordLibraryScreen(MeetingRecordRepository(fixture) { "reader" }, "reader", false, {}, {}) } }
        awaitText("Private planning meeting")
        openMore()
        compose.onNodeWithText(label(R.string.records_filters)).performClick()
        compose.onNodeWithText(label(R.string.records_created_from)).performScrollTo().performTextInput("2026-09-20")
        compose.onNodeWithText(label(R.string.records_filters_done)).performScrollTo().performClick()
        awaitText(label(R.string.records_unavailable))
        compose.onNodeWithText("Private planning meeting").assertDoesNotExist()
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
        // 提交只剩键盘上的搜索键（参考稿与 Web 端都没有独立的搜索按钮），
        // 所以走 IME action，而不是点一颗已经删掉的按钮。
        compose.onNodeWithText(label(R.string.records_search_originals)).performImeAction()
        awaitText("Search matched original")
        clickTranscriptAction(R.string.records_filter_speaker)
        awaitText(label(R.string.records_all_speakers))
        compose.onAllNodesWithText("Speaker 1")[1].performClick()
        compose.waitUntil(5_000) { fixture.originalQueries.lastOrNull() == ("release 中文" to versionId) }
        clickTranscriptAction(R.string.records_clear_filters)
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
