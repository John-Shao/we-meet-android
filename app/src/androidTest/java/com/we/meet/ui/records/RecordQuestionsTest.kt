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
import com.we.meet.data.api.MeetingQuestionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingQuestionRepository
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
class RecordQuestionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "question-ui-${UUID.randomUUID()}"
    private val record = RecordDto(UUID.randomUUID().toString(), "audio_recording", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(true, true, true))
    private val snapshot = UUID.randomUUID().toString()
    private val ref = RecordReferenceDto(UUID.randomUUID().toString(), 2, 200, 900)
    private val version = RecordSummaryVersionDto(UUID.randomUUID().toString(), "final", snapshot, 2, true, "2026-09-13T00:00:00Z", "complete",
        content = RecordSummaryContentDto("Overview", emptyList(), emptyList(), emptyList(), emptyList()))
    private val api = Fixture()
    private val sources = CopyOnWriteArrayList<Pair<String, RecordReferenceDto>>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(dark: Boolean = false, originals: Boolean = true) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordQuestions(viewer, record.copy(capabilities = record.capabilities.copy(readTranscript = originals)), listOf(version), MeetingQuestionRepository(api) { viewer }, { viewer }) { id, ref -> sources += id to ref }
        } } } }
    }
    private fun composeQuestion() {
        await(R.string.record_question_choose)
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.record_question_choose)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText(label(R.string.record_question_choose)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.record_question_source, 2, recordTime(version.createdAt))).performClick()
        compose.onNodeWithText(label(R.string.record_question_input)).performScrollTo().performTextReplacement("What did we decide?")
    }
    private fun ask() {
        composeQuestion()
        compose.waitUntil(8000) { compose.onNodeWithText(label(R.string.record_question_ask)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
        compose.onNodeWithText(label(R.string.record_question_ask)).performScrollTo().performClick()
    }
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "record-question-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun explicitSourceAndClickAreRequiredAndAnswerUsesExactCitation() {
        show()
        await(R.string.record_question_ask)
        compose.onNodeWithText(label(R.string.record_question_ask)).assertIsNotEnabled()
        assertTrue(api.bodies.isEmpty())
        ask(); await(R.string.record_question_succeeded)
        compose.onNodeWithText("Grounded answer").assertExists()
        val input = MeetingQuestionRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals(snapshot, input.snapshotId)
        compose.onNodeWithText(label(R.string.records_source) + " · " + sourceTime(ref.startMs)).performScrollTo().performClick()
        assertEquals(listOf(snapshot to ref), sources.toList())
        screenshot("answer")
    }
    @Test fun unknownRequestRestoresOriginalQuestionEvenWhenNewQuestionsAreDisabled() {
        api.status = 503
        show(); ask(); await(R.string.summary_controls_reconcile)
        val body = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile)
        assertEquals(1, api.bodies.size)
        api.status = 200
        compose.onNodeWithText(label(R.string.summary_controls_reconcile)).performScrollTo().performClick()
        await(R.string.record_question_succeeded)
        assertEquals(body, api.bodies.last())
        assertEquals(1, api.executions)
    }
    @Test fun runningQuestionPollsWithoutSubmittingAgain() {
        api.answerStatus = "running"
        show(); ask(); await(R.string.record_question_running)
        compose.onNodeWithText(label(R.string.record_question_ask)).assertIsNotEnabled()
        api.answerStatus = "succeeded"
        await(R.string.record_question_succeeded)
        assertEquals(1, api.bodies.size)
        assertTrue(api.reads.size >= 2)
        assertEquals(setOf(api.questionId to snapshot), api.reads.toSet())
    }
    @Test fun unanswerableSourceDoesNotDisplayFabricatedAnswerOrCitation() {
        api.answerable = false
        show(dark = true); ask(); await(R.string.record_question_no_answer)
        compose.onNodeWithText("Grounded answer").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.records_source) + " · " + sourceTime(ref.startMs)).assertDoesNotExist()
        screenshot("no-answer-dark")
    }
    @Test fun backgroundReturnRechecksAndRevocationHidesPrivateAnswer() {
        show(); ask(); await(R.string.record_question_succeeded)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.failRead = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.record_question_read_error)
        compose.onNodeWithText("Grounded answer").assertDoesNotExist()
        assertEquals(1, api.bodies.size)
    }
    @Test fun summaryOnlyReadersNeverFetchOrAskPrivateQuestions() {
        show(originals = false)
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.record_question_title)).assertDoesNotExist()
        assertEquals(0, api.availabilityReads)
        assertTrue(api.bodies.isEmpty())
    }
    private inner class Fixture : MeetingQuestionApi {
        @Volatile var status = 200
        @Volatile var available = true
        @Volatile var failRead = false
        @Volatile var answerStatus = "succeeded"
        @Volatile var answerable = true
        @Volatile var availabilityReads = 0
        var executions = 0
        val questionId = UUID.randomUUID().toString()
        val bodies = CopyOnWriteArrayList<String>()
        val reads = CopyOnWriteArrayList<Pair<String, String>>()
        var input: RecordQuestionRequestDto? = null
        private fun answer() = RecordQuestionDto(questionId, requireNotNull(input).snapshotId, answerStatus, requireNotNull(input).question,
            if (answerStatus != "succeeded") null else if (answerable) RecordQuestionContentDto(true, "Grounded answer", listOf(ref)) else RecordQuestionContentDto(false, "", emptyList()), "")
        private fun access() { if (failRead) throw HttpException(Response.error<Any>(403, "{}".toResponseBody())) }
        override suspend fun recent(record: String): RecordQuestionsDto { access(); availabilityReads++; return RecordQuestionsDto(available, if (input == null) emptyList() else listOf(answer())) }
        override suspend fun question(record: String, question: String): RecordQuestionDto { access(); reads += question to snapshot; return answer() }
        override suspend fun ask(record: String, body: RequestBody): RecordQuestionDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            if (input == null) { executions++; input = requireNotNull(MeetingQuestionRepository.requestAdapter.fromJson(text)) }
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return answer()
        }
    }
}
