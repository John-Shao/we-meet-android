package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingQuestionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingQuestionRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MeetingQuestionCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "question-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = RecordQuestionRequestDto(UUID.randomUUID().toString(), "Private question")
    private val api = Fixture()
    private fun coordinator() = MeetingQuestionCoordinator(viewer, store, MeetingQuestionRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun unknownQuestionSurvivesRestartAndKeepsExactOriginalSnapshot() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().ask(record, input) }.isFailure)
        val frozen = coordinator().pending(record)!!
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(record)); assertEquals(1, api.bodies.size)
        api.status = 200
        coordinator().ask(record, input.copy(snapshotId = record, question = "Other question"))
        assertEquals(api.bodies.first(), api.bodies.last()); assertNull(coordinator().pending(record))
    }
    @Test fun malformedSuccessfulAnswerRetainsOriginalRequest() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().ask(record, input) }.isFailure)
        assertNotNull(coordinator().pending(record))
        api.invalid = false
        coordinator().ask(record, input)
        assertEquals(api.bodies.first(), api.bodies.last())
    }
    @Test fun definitiveConflictDoesNotResolveAnotherOperation() = runBlocking {
        val other = store.getOrCreate(MeetingIntentKind.HUMAN_REVIEW, record, "{}")
        api.status = 409
        assertTrue(runCatching { coordinator().ask(record, input) }.isFailure)
        assertNull(coordinator().pending(record)); assertEquals(other, store.get(MeetingIntentKind.HUMAN_REVIEW, record))
    }
    @Test fun invalidInputCannotCreateRecoveryEntry() = runBlocking {
        assertTrue(runCatching { coordinator().ask(record, input.copy(question = "")) }.isFailure)
        assertNull(coordinator().pending(record)); assertTrue(api.bodies.isEmpty())
    }
    private class Fixture : MeetingQuestionApi {
        var status = 200
        var invalid = false
        val bodies = mutableListOf<String>()
        override suspend fun ask(record: String, body: RequestBody): RecordQuestionDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val request = requireNotNull(MeetingQuestionRepository.requestAdapter.fromJson(text))
            return RecordQuestionDto(UUID.randomUUID().toString(), if (invalid) record else request.snapshotId, "running", request.question, null, "")
        }
        override suspend fun recent(record: String): RecordQuestionsDto = error("No automatic reads")
        override suspend fun question(record: String, question: String): RecordQuestionDto = error("No automatic reads")
    }
}
