package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingReviewApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingReviewRepository
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
class MeetingReviewCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "review-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private val base = UUID.randomUUID().toString()
    private val content = HumanContentDto("Private human draft", emptyList(), emptyList(), emptyList(), emptyList())
    private val request = HumanReviewRequestDto(base, 0, false, content)
    private val task = SummaryTaskRequestDto(base, 0, "Private assigned task", record, null)
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val api = Fixture()
    private fun coordinator() = MeetingReviewCoordinator(viewer, store, MeetingReviewRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun unknownHumanSaveSurvivesRestartAndPreservesOriginalContent() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().save(record, request) }.isFailure)
        val frozen = coordinator().pending(record, MeetingIntentKind.HUMAN_REVIEW)!!
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(record, MeetingIntentKind.HUMAN_REVIEW))
        assertEquals(1, api.bodies.size)
        api.status = 200
        coordinator().save(record, request.copy(content = content.copy(overview = "Different draft")))
        assertEquals(api.bodies.first(), api.bodies.last())
        assertNull(coordinator().pending(record, MeetingIntentKind.HUMAN_REVIEW))
    }
    @Test fun malformedTaskSuccessRemainsPendingAndReconcilesWithoutNewAssignment() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().convert(record, task) }.isFailure)
        assertNotNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_TASK))
        api.invalid = false
        coordinator().convert(record, task.copy(title = "Other", assigneeId = base))
        assertEquals(api.bodies.first(), api.bodies.last())
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_TASK))
    }
    @Test fun conflictResolvesOnlyExactOperationAndUnknown408RetainsIt() = runBlocking {
        val untouched = store.getOrCreate(MeetingIntentKind.HUMAN_REVIEW, record, "{}")
        api.status = 408
        assertTrue(runCatching { coordinator().convert(record, task) }.isFailure)
        assertNotNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_TASK))
        api.status = 409
        assertTrue(runCatching { coordinator().convert(record, task) }.isFailure)
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_TASK))
        assertEquals(untouched, coordinator().pending(record, MeetingIntentKind.HUMAN_REVIEW))
    }
    @Test fun invalidInputDoesNotPoisonEncryptedRecovery() = runBlocking {
        assertTrue(runCatching { coordinator().save(record, request.copy(expectedRevision = -1)) }.isFailure)
        assertTrue(runCatching { coordinator().convert(record, task.copy(assigneeId = "AI label")) }.isFailure)
        assertNull(coordinator().pending(record, MeetingIntentKind.HUMAN_REVIEW))
        assertNull(coordinator().pending(record, MeetingIntentKind.SUMMARY_TASK))
        assertTrue(api.bodies.isEmpty())
    }
    private inner class Fixture : MeetingReviewApi {
        var status = 200
        var invalid = false
        val bodies = mutableListOf<String>()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private fun received(body: RequestBody): String {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return text
        }
        override suspend fun save(record: String, body: RequestBody): HumanReviewAcceptedDto {
            val input = requireNotNull(MeetingReviewRepository.reviewAdapter.fromJson(received(body)))
            val saved = HumanReviewDto(UUID.randomUUID().toString(), input.expectedRevision + 1, input.baseSummaryId, null, base, null,
                "2026-09-13T00:00:00Z", input.content, "human", 1)
            return HumanReviewAcceptedDto(saved, saved, true)
        }
        override suspend fun convert(record: String, body: RequestBody): SummaryTaskAcceptedDto {
            val input = requireNotNull(MeetingReviewRepository.taskAdapter.fromJson(received(body)))
            return SummaryTaskAcceptedDto(SummaryTaskLinkDto(if (invalid) "bad" else UUID.randomUUID().toString(), null, null, true, input.reviewId), false)
        }
        override suspend fun current(record: String): HumanReviewStateDto = error("Unexpected read")
        override suspend fun history(record: String, before: Int?): HumanReviewHistoryDto = error("Unexpected read")
        override suspend fun version(record: String, review: String): HumanReviewDto = error("Unexpected read")
        override suspend fun tasks(record: String, query: String?): SummaryTasksStateDto = error("Unexpected read")
    }
}
