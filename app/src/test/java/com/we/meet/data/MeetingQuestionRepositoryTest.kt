package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingQuestionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingQuestionRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingQuestionRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val snapshot = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val input = RecordQuestionRequestDto(snapshot, "What was decided?")
    private val answer = RecordQuestionDto(id, snapshot, "succeeded", input.question,
        RecordQuestionContentDto(true, "The cited decision", listOf(RecordReferenceDto(key, 2, 10, 20))), "")
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private fun json(value: RecordQuestionDto) = moshi.adapter(RecordQuestionDto::class.java).serializeNulls().toJson(value)
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingQuestionRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host)
            assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return MeetingQuestionRepository(Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingQuestionApi::class.java)) { viewer }
    }
    @Test fun askSendsExplicitSnapshotAndBodyKeyAndReturnsExactCitations() = runBlocking {
        val repository = repository {
            assertEquals("/api/v1.0/meeting-records/$record/questions/", it.url.encodedPath)
            val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
            assertTrue(body.contains("\"key\":\"$key\"") && body.contains("\"snapshot_id\":\"$snapshot\""))
            200 to json(answer)
        }
        assertEquals(answer, repository.ask("owner", record, key, input).getOrThrow())
    }
    @Test fun unknownSuccessOrWrongSnapshotOrQuestionCannotResolveIntent() = runBlocking {
        assertTrue(repository { 200 to "{}" }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(snapshotId = record)) }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(question = "Other question")) }.ask("owner", record, key, input).isFailure)
    }
    @Test fun successfulAnswersRequireGroundingAndUnanswerableMeansNoContent() = runBlocking {
        assertTrue(repository { 200 to json(answer.copy(content = null)) }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(content = answer.content!!.copy(sourceRefs = emptyList()))) }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(content = answer.content!!.copy(answerable = false))) }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(content = RecordQuestionContentDto(false, "", emptyList()))) }.ask("owner", record, key, input).isSuccess)
    }
    @Test fun nonSuccessfulJobsNeverExposeAnOldAnswer() = runBlocking {
        assertTrue(repository { 200 to json(answer.copy(status = "running")) }.ask("owner", record, key, input).isFailure)
        assertTrue(repository { 200 to json(answer.copy(status = "incomplete", content = null)) }.ask("owner", record, key, input).isSuccess)
    }
    @Test fun invalidQuestionAndSnapshotFailBeforeHttp() = runBlocking {
        val repository = repository { error("No dispatch") }
        listOf(input.copy(snapshotId = "latest"), input.copy(question = " "), input.copy(question = " leading"), input.copy(question = "x".repeat(2001))).forEach {
            assertTrue(repository.ask("owner", record, key, it).isFailure)
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun privateAnswerIsDroppedAfterAccountSwitch() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(answer) }.question("owner", record, id, snapshot).isFailure)
    }
    @Test fun recentReadsAreBoundedAndAvailabilityFailsClosed() = runBlocking {
        assertFalse(repository { 200 to "{}" }.recent("owner", record).getOrThrow().available)
        val duplicates = moshi.adapter(RecordQuestionsDto::class.java).serializeNulls().toJson(RecordQuestionsDto(true, listOf(answer, answer)))
        assertTrue(repository { 200 to duplicates }.recent("owner", record).isFailure)
    }
    @Test fun exactQuestionReadRejectsAnotherIdOrBadReferenceCoordinates() = runBlocking {
        assertTrue(repository { 200 to json(answer.copy(id = key)) }.question("owner", record, id, snapshot).isFailure)
        val bad = answer.copy(content = answer.content!!.copy(sourceRefs = listOf(RecordReferenceDto(key, 1, 20, 10))))
        assertTrue(repository { 200 to json(bad) }.question("owner", record, id, snapshot).isFailure)
    }
}
