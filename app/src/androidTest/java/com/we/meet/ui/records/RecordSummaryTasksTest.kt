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
class RecordSummaryTasksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "task-ui-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private val person = SummaryAssigneeDto(UUID.randomUUID().toString(), "Explicit person")
    private val other = SummaryAssigneeDto(UUID.randomUUID().toString(), "Other person")
    private val task = UUID.randomUUID().toString()
    private val review = HumanReviewDto(UUID.randomUUID().toString(), 1, record, null, record, null, "2026-09-13T00:00:00Z",
        HumanContentDto("Overview", emptyList(), emptyList(), listOf(HumanActionDto("Private action", emptyList(), "AI person", "Next week")), emptyList()), "human", 1)
    private val api = Fixture()
    private val opened = CopyOnWriteArrayList<String>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordSummaryTasks(viewer, record, review, MeetingReviewRepository(api) { viewer }, { viewer }) { opened += it }
        } } } }
    }
    private fun edit() {
        await(R.string.summary_tasks_convert)
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.summary_tasks_convert)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText(label(R.string.summary_tasks_convert)).performScrollTo().performClick()
        await(R.string.summary_tasks_create)
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "summary-tasks-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun explicitConfirmationDoesNotInferAssigneeOrDateAndOpensExactNativeTask() {
        show(); edit()
        assertTrue(api.bodies.isEmpty())
        compose.onNodeWithText(label(R.string.summary_tasks_create)).assertIsNotEnabled()
        compose.onNodeWithText(person.name).performScrollTo().performClick()
        screenshot("confirm", dialog = true)
        compose.onNodeWithText(label(R.string.summary_tasks_create)).performClick()
        await(R.string.summary_tasks_open)
        val request = MeetingReviewRepository.taskAdapter.fromJson(api.bodies.single())!!
        assertEquals(person.id, request.assigneeId)
        assertNull(request.dueDate)
        assertEquals(review.id, request.reviewId)
        assertEquals(0, request.actionIndex)
        compose.onNodeWithText(label(R.string.summary_tasks_open)).performScrollTo().performClick()
        assertEquals(listOf(task), opened.toList())
    }
    @Test fun assigneeSearchClearsPreviousSelectionBeforeSending() {
        show(); edit()
        compose.onNodeWithText(person.name).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.summary_tasks_search)).performTextReplacement("Other")
        compose.onNodeWithText(label(R.string.records_search_action)).performScrollTo().performClick()
        compose.waitUntil(8000) { api.queries.contains("Other") && compose.onAllNodesWithText(other.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.summary_tasks_create)).assertIsNotEnabled()
        compose.onNodeWithText(other.name).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.summary_tasks_create)).performClick()
        await(R.string.summary_tasks_open)
        assertEquals(other.id, MeetingReviewRepository.taskAdapter.fromJson(api.bodies.single())!!.assigneeId)
    }
    @Test fun unknownTaskCreationSurvivesBackgroundAndReplaysOriginalPayload() {
        api.status = 503
        show(); edit()
        compose.onNodeWithText(person.name).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.summary_tasks_create)).performClick()
        await(R.string.summary_controls_reconcile)
        val body = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile)
        assertEquals(1, api.bodies.size)
        api.status = 200
        compose.onNodeWithText(label(R.string.summary_controls_reconcile)).performScrollTo().performClick()
        await(R.string.summary_tasks_open)
        assertEquals(body, api.bodies.last())
    }
    @Test fun deletedTaskReceiptNeverOffersRecreationOrInaccessibleNavigation() {
        api.link = SummaryTaskLinkDto(record, null, null, true, review.id)
        api.canConvert = false
        show(dark = true)
        await(R.string.summary_tasks_deleted)
        compose.onNodeWithText(label(R.string.summary_tasks_convert)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.summary_tasks_open)).assertDoesNotExist()
        screenshot("deleted-dark")
        assertTrue(api.bodies.isEmpty())
    }
    @Test fun changedReviewCannotConvertOldActionIndex() {
        api.reviewId = UUID.randomUUID().toString()
        show()
        await(R.string.summary_tasks_conflict)
        compose.onNodeWithText(label(R.string.summary_tasks_convert)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }
    @Test fun returningAfterRevocationHidesTaskTitleAndConfirmation() {
        show(); edit()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.failRead = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_tasks_read_error)
        compose.onNodeWithText("Private action").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.summary_tasks_create)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }
    private inner class Fixture : MeetingReviewApi {
        @Volatile var canConvert = true
        @Volatile var status = 200
        @Volatile var failRead = false
        @Volatile var reviewId = review.id
        @Volatile var link: SummaryTaskLinkDto? = null
        val bodies = CopyOnWriteArrayList<String>()
        val queries = CopyOnWriteArrayList<String?>()
        override suspend fun tasks(record: String, query: String?): SummaryTasksStateDto {
            queries += query
            if (failRead) throw HttpException(Response.error<Any>(403, "{}".toResponseBody()))
            return SummaryTasksStateDto(canConvert, reviewId, if (canConvert) listOf(if (query == "Other") other else person) else emptyList(), listOf(link))
        }
        override suspend fun convert(record: String, body: RequestBody): SummaryTaskAcceptedDto {
            bodies += Buffer().also { body.writeTo(it) }.readUtf8()
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val created = SummaryTaskLinkDto(UUID.randomUUID().toString(), task, "todo", false, review.id)
            link = created
            return SummaryTaskAcceptedDto(created, true)
        }
        override suspend fun current(record: String): HumanReviewStateDto = error("Unexpected edit")
        override suspend fun save(record: String, body: RequestBody): HumanReviewAcceptedDto = error("Unexpected edit")
        override suspend fun history(record: String, before: Int?): HumanReviewHistoryDto = error("Unexpected edit")
        override suspend fun version(record: String, review: String): HumanReviewDto = error("Unexpected edit")
    }
}
