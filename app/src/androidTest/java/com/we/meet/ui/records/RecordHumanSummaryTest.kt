package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingReviewApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingReviewRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class RecordHumanSummaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "human-ui-${UUID.randomUUID()}"
    private val id = UUID.randomUUID().toString()
    private val snapshot = UUID.randomUUID().toString()
    private val base = UUID.randomUUID().toString()
    private val ref = RecordReferenceDto(UUID.randomUUID().toString(), 2, 300, 900)
    private val content = HumanContentDto("Private reviewed overview", listOf(HumanPointDto("Decision with source", listOf(ref))), emptyList(), emptyList(), emptyList())
    private val record = RecordDto(id, "audio_recording", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(true, true, true))
    private val api = Fixture()
    private val sources = CopyOnWriteArrayList<Pair<String, RecordReferenceDto>>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false, ai: RecordSummaryVersionDto? = null) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordHumanSummary(viewer, record, ai, MeetingReviewRepository(api) { viewer }, { viewer }) { snapshot, ref -> sources += snapshot to ref }
        } } } }
        compose.waitUntil(8000) { compose.onAllNodesWithText(if (api.hasReview) "Private reviewed overview" else label(R.string.human_summary_empty)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun edit() {
        await(R.string.human_summary_edit)
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.human_summary_edit)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText(label(R.string.human_summary_edit)).performScrollTo().performClick()
        await(R.string.human_summary_save)
    }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "human-summary-$name.png").outputStream().use {
            (if (name == "editor") compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun readOnlyViewHasExactSourcesAndHistoryWithoutSaving() {
        api.canEdit = false
        show(dark = true)
        compose.onNodeWithText(label(R.string.human_summary_edit)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_source) + " · " + sourceTime(ref.startMs)).performScrollTo().performClick()
        assertEquals(listOf(snapshot to ref), sources.toList())
        screenshot("read-only-dark")
        compose.onNodeWithText(label(R.string.human_summary_history)).performScrollTo().performClick()
        compose.waitUntil(8000) { api.historyReads > 0 }
        compose.onNodeWithText(context.getString(R.string.human_summary_version, 1) + " · " + recordTime(api.current.createdAt)).performClick()
        compose.waitUntil(8000) { api.versionReads.isNotEmpty() }
        assertEquals(listOf(api.current.id), api.versionReads.toList())
        assertTrue(api.bodies.isEmpty())
    }
    @Test fun editAppendsHumanRevisionAndPreservesExactReferences() {
        show()
        assertTrue(api.bodies.isEmpty())
        edit()
        compose.onNodeWithText(label(R.string.human_summary_overview)).performTextReplacement("Updated private overview")
        screenshot("editor")
        compose.onNodeWithText(label(R.string.human_summary_save)).performClick()
        compose.waitUntil(8000) { api.bodies.size == 1 && compose.onAllNodesWithText("Updated private overview").fetchSemanticsNodes().isNotEmpty() }
        val input = MeetingReviewRepository.reviewAdapter.fromJson(api.bodies.single())!!
        assertEquals(1, input.expectedRevision)
        assertEquals(base, input.baseSummaryId)
        assertEquals(listOf(ref), input.content.decisions.single().sourceRefs)
        assertFalse(input.replaceBase)
        compose.onNodeWithText(label(R.string.human_summary_save)).assertDoesNotExist()
        screenshot("saved")
    }
    @Test fun firstHumanRevisionUsesExplicitAiVersionWithoutGeneratingOrCreatingTasks() {
        api.hasReview = false
        val ai = RecordSummaryVersionDto(base, "final", snapshot, 2, true, "2026-09-13T00:00:00Z", "complete", content =
            RecordSummaryContentDto("AI overview", listOf(RecordSummaryPointDto("AI decision", listOf(ref))), emptyList(), emptyList(), emptyList()))
        show(ai = ai); edit()
        compose.onNodeWithText(label(R.string.human_summary_save)).performClick()
        compose.waitUntil(8000) { api.bodies.size == 1 && compose.onAllNodesWithText(label(R.string.human_summary_save)).fetchSemanticsNodes().isEmpty() }
        val input = MeetingReviewRepository.reviewAdapter.fromJson(api.bodies.single())!!
        assertEquals(0, input.expectedRevision)
        assertEquals(base, input.baseSummaryId)
        assertEquals(listOf(ref), input.content.decisions.single().sourceRefs)
    }
    @Test fun lostSaveResponseSurvivesBackgroundAndReplaysOriginalDraft() {
        api.status = 503
        show(); edit()
        compose.onNodeWithText(label(R.string.human_summary_save)).performClick()
        await(R.string.summary_controls_reconcile)
        val original = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile)
        assertEquals(1, api.bodies.size)
        api.status = 200
        compose.onNodeWithText(label(R.string.summary_controls_reconcile)).performScrollTo().performClick()
        compose.waitUntil(8000) { api.bodies.size == 2 && compose.onAllNodesWithText(label(R.string.summary_controls_reconcile)).fetchSemanticsNodes().isEmpty() }
        assertEquals(original, api.bodies.last())
    }
    @Test fun conflictingSaveRetainsDraftAndPreventsOverwritingNewerRevision() {
        api.status = 409
        show(); edit()
        compose.onNodeWithText(label(R.string.human_summary_overview)).performTextReplacement("Keep this draft")
        api.current = api.current.copy(revision = 2)
        compose.onNodeWithText(label(R.string.human_summary_save)).performClick()
        await(R.string.human_summary_conflict)
        compose.onNodeWithText("Keep this draft").assertExists()
        compose.onNodeWithText(label(R.string.human_summary_save)).assertIsNotEnabled()
        assertEquals(1, api.bodies.size)
    }
    @Test fun returningAfterPermissionLossHidesEditorAndPrivateBody() {
        show(); edit()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.failRead = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.human_summary_read_error)
        compose.onNodeWithText("Private reviewed overview").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.human_summary_save)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }
    private inner class Fixture : MeetingReviewApi {
        var canEdit = true
        @Volatile var hasReview = true
        @Volatile var failRead = false
        @Volatile var status = 200
        @Volatile var historyReads = 0
        @Volatile var current = HumanReviewDto(UUID.randomUUID().toString(), 1, base, null, snapshot, null, "2026-09-13T00:00:00Z", content, "human", 2)
        val bodies = CopyOnWriteArrayList<String>()
        val versionReads = CopyOnWriteArrayList<String>()
        override suspend fun current(record: String): HumanReviewStateDto {
            if (failRead) throw HttpException(Response.error<Any>(403, "{}".toResponseBody()))
            return HumanReviewStateDto(if (hasReview) current else null, canEdit)
        }
        override suspend fun save(record: String, body: RequestBody): HumanReviewAcceptedDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val input = requireNotNull(MeetingReviewRepository.reviewAdapter.fromJson(text))
            current = current.copy(id = UUID.randomUUID().toString(), revision = input.expectedRevision + 1, content = input.content)
            hasReview = true
            return HumanReviewAcceptedDto(current, current, false)
        }
        override suspend fun history(record: String, before: Int?): HumanReviewHistoryDto {
            historyReads++; return HumanReviewHistoryDto(listOf(HumanReviewHistoryRowDto(current.id, current.revision, current.baseSummaryId, current.createdAt)), null)
        }
        override suspend fun version(record: String, review: String): HumanReviewDto { versionReads += review; return current }
        override suspend fun tasks(record: String, query: String?): SummaryTasksStateDto = error("Never creates tasks")
        override suspend fun convert(record: String, body: RequestBody): SummaryTaskAcceptedDto = error("Never creates tasks")
    }
}
