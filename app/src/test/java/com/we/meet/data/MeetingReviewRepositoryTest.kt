package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingReviewApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingReviewRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingReviewRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val base = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val content = HumanContentDto("Human overview", listOf(HumanPointDto("Decision", listOf(RecordReferenceDto(id, 1, 20, null)))),
        emptyList(), listOf(HumanActionDto("Follow up", emptyList(), "Unconfirmed person", "Next week")), emptyList())
    private val save = HumanReviewRequestDto(base, 0, false, content)
    private val task = SummaryTaskRequestDto(id, 0, "Confirmed title", base, null)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private fun review(revision: Int = 1) = HumanReviewDto(id, revision, base, null, key, null, "2026-09-13T00:00:00Z", content, "human", 1)
    private fun saved(value: HumanReviewAcceptedDto) = moshi.adapter(HumanReviewAcceptedDto::class.java).serializeNulls().toJson(value)
    private fun link(deleted: Boolean = false, review: String = id) = """{"id":"$key","task_id":null,"status":null,"deleted":$deleted,"review_id":"$review"}"""
    private fun repo(reply: (Request) -> Pair<Int, String>): MeetingReviewRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (status, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingReviewApi::class.java)
        return MeetingReviewRepository(api) { viewer }
    }
    @Test fun reviewUsesBodyKeyAndExactStructureIncludingNullableSourceEnd() = runBlocking {
        val repository = repo {
            assertEquals("/api/v1.0/meeting-records/$record/human-summary/", it.url.encodedPath)
            val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
            assertTrue(body.contains("\"key\":\"$key\""))
            assertTrue(body.contains("\"end_ms\":null"))
            assertFalse(body.contains("\"owner_text\":null"))
            200 to saved(HumanReviewAcceptedDto(review(), review(2), true))
        }
        assertEquals(2, repository.save("owner", record, key, save).getOrThrow().current.revision)
    }
    @Test fun missingOrMismatchedSuccessIsNotAccepted() = runBlocking {
        assertTrue(repo { 200 to "{}" }.save("owner", record, key, save).isFailure)
        assertTrue(repo { 200 to saved(HumanReviewAcceptedDto(review(2), review(2), true)) }.save("owner", record, key, save).isFailure)
        assertTrue(repo { 200 to saved(HumanReviewAcceptedDto(review().copy(content = content.copy(overview = "Other")), review(), true)) }
            .save("owner", record, key, save).isFailure)
    }
    @Test fun taskHasExplicitAssigneeAndRequiredNullDateAndReusesOlderDeletedLink() = runBlocking {
        val repository = repo {
            assertEquals("/api/v1.0/meeting-records/$record/summary-tasks/", it.url.encodedPath)
            val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
            assertTrue(body.contains("\"due_date\":null") && body.contains("\"assignee_id\":\"$base\""))
            200 to """{"created":false,"link":${link(true, base)}}"""
        }
        val result = repository.convert("owner", record, key, task).getOrThrow()
        assertFalse(result.created); assertTrue(result.link.deleted); assertNull(result.link.taskId)
    }
    @Test fun newlyCreatedReceiptCannotPointToAnotherReviewOrDeletedTask() = runBlocking {
        assertTrue(repo { 200 to """{"created":true,"link":${link(false, base)}}""" }.convert("owner", record, key, task).isFailure)
        assertTrue(repo { 200 to """{"created":true,"link":${link(true)}}""" }.convert("owner", record, key, task).isFailure)
    }
    @Test fun invalidInputIsRejectedBeforeNetwork() = runBlocking {
        val repository = repo { error("No network") }
        assertTrue(repository.convert("owner", record, key, task.copy(assigneeId = "AI name")).isFailure)
        assertTrue(repository.convert("owner", record, key, task.copy(dueDate = "2026-02-30")).isFailure)
        assertTrue(repository.save("owner", record, key, save.copy(content = content.copy(overview = " "))).isFailure)
        assertTrue(repository.save("owner", record, key, save.copy(content = content.copy(actionItems = List(100) { HumanActionDto("字".repeat(4000), emptyList(), "", "") }))).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun revokedAccountDiscardsLatePrivateRead() = runBlocking {
        val repository = repo { viewer = null; 200 to moshi.adapter(HumanReviewStateDto::class.java).serializeNulls().toJson(HumanReviewStateDto(review(), true)) }
        assertTrue(repository.current("owner", record).isFailure)
    }
    @Test fun historyHasStrictDescendingCoordinatesAndExactVersion() = runBlocking {
        val row = HumanReviewHistoryRowDto(id, 2, base, "2026-09-13T00:00:00Z")
        val json = moshi.adapter(HumanReviewHistoryDto::class.java).serializeNulls().toJson(HumanReviewHistoryDto(listOf(row), null))
        assertTrue(repo { 200 to json }.history("owner", record, 3).isSuccess)
        assertTrue(repo { 200 to json }.history("owner", record, 2).isFailure)
        val wrong = moshi.adapter(HumanReviewDto::class.java).serializeNulls().toJson(review().copy(id = base))
        assertTrue(repo { 200 to wrong }.version("owner", record, id).isFailure)
    }
    @Test fun directoryQueryIsEncodedAndMissingCapabilityFailsClosed() = runBlocking {
        val repository = repo {
            assertEquals("A & B", it.url.queryParameter("q"))
            200 to """{"review_id":null,"assignees":[],"actions":[]}"""
        }
        assertFalse(repository.tasks("owner", record, "A & B").getOrThrow().canConvert)
        assertTrue(repo { 200 to """{"review_id":null,"assignees":[],"actions":[${link()}]}""" }.tasks("owner", record).isFailure)
    }
}
