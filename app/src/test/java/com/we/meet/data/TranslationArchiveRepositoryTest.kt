package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.TranslationArchiveApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.TranslationArchiveRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TranslationArchiveRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val archive = TranslationArchiveDto(UUID.randomUUID().toString(), "private", "push_to_talk", "zh", "en", 1, "incomplete", 2, "2026-09-13T00:00:00Z")
    private val row = TranslationSegmentDto(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), "PA_speaker", "Speaker", "forward", "en", "Confirmed translation", "2026-09-13T00:00:01Z", "delivery", null)
    private val page = TranslationSegmentPageDto(listOf(row), null, archive.id, archive.status, archive.target, archive.sourceKind, archive.mode, archive.source)
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun repository(reply: (Request) -> Pair<Int, String>): TranslationArchiveRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("GET", request.method); assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, value) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(value.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TranslationArchiveApi::class.java)
        return TranslationArchiveRepository(api) { viewer }
    }
    @Test fun readsExactRecordArchiveAndOpaqueCursorWithoutFollowingLinks() = runBlocking {
        val repo = repository {
            assertEquals("/api/v1.0/meeting-records/$record/translation-segments/", it.url.encodedPath)
            assertEquals(archive.id, it.url.queryParameter("archive_id")); assertEquals("next+/=", it.url.queryParameter("cursor"))
            200 to json(page)
        }
        assertEquals(page, repo.segments("owner", record, archive, "next+/=").getOrThrow())
    }
    @Test fun archiveListingValidatesKindsLanguagesStatusAndBounds() = runBlocking {
        val good = TranslationArchivePageDto(listOf(archive), null)
        assertTrue(repository { 200 to json(good) }.archives("owner", record).isSuccess)
        for (bad in listOf(archive.copy(sourceKind = "other"), archive.copy(source = "en"), archive.copy(status = "success"), archive.copy(generation = 0), archive.copy(segmentCount = -1), archive.copy(createdAt = "yesterday"), archive.copy(sourceKind = "channel"))) {
            assertTrue(repository { 200 to json(good.copy(results = listOf(bad))) }.archives("owner", record).isFailure)
        }
        assertTrue(repository { 200 to json(good.copy(results = listOf(archive, archive))) }.archives("owner", record).isFailure)
        assertTrue(repository { 200 to json(good.copy(nextCursor = "same")) }.archives("owner", record, "same").isFailure)
    }
    @Test fun pageCannotSubstituteArchiveOrChangeItsProvenance() = runBlocking {
        for (bad in listOf(page.copy(archiveId = record), page.copy(sourceKind = "channel"), page.copy(mode = "simultaneous"), page.copy(target = "zh"), page.copy(source = "en"))) {
            assertTrue(repository { 200 to json(bad) }.segments("owner", record, archive).isFailure)
        }
    }
    @Test fun translationsAreNotOriginalSpeechOffsetsOrOriginalCitations() = runBlocking {
        for (bad in listOf(row.copy(timingBasis = "original"), row.copy(originalId = record), row.copy(sequence = 0), row.copy(sourceParticipantSid = "unbound"), row.copy(text = " "), row.copy(text = "x".repeat(20001)))) {
            assertTrue(repository { 200 to json(page.copy(results = listOf(bad))) }.segments("owner", record, archive).isFailure)
        }
        assertFalse(row.toString().contains(row.text))
    }
    @Test fun reverseDirectionRequiresPersonalManualTranslationAndOppositeTarget() = runBlocking {
        val reverse = row.copy(direction = "reverse", target = "zh")
        assertTrue(repository { 200 to json(page.copy(results = listOf(reverse))) }.segments("owner", record, archive).isSuccess)
        assertTrue(repository { 200 to json(page.copy(results = listOf(reverse.copy(target = "en")))) }.segments("owner", record, archive).isFailure)
        val continuous = archive.copy(mode = "simultaneous")
        assertTrue(repository { 200 to json(page.copy(mode = "simultaneous", results = listOf(reverse))) }.segments("owner", record, continuous).isFailure)
    }
    @Test fun segmentPagesAreBoundedUniqueAndOrdered() = runBlocking {
        for (rows in listOf(listOf(row, row), listOf(row.copy(sequence = 2), row.copy(id = record, sequence = 1)), (1L..51L).map { row.copy(id = UUID.randomUUID().toString(), sequence = it) })) {
            assertTrue(repository { 200 to json(page.copy(results = rows)) }.segments("owner", record, archive).isFailure)
        }
    }
    @Test fun accountSwitchAndAccessFailureNeverReturnRetainedText() = runBlocking {
        val repo = repository { viewer = "different"; 200 to json(page) }
        assertTrue(repo.segments("owner", record, archive).isFailure)
        val count = requests.size
        assertTrue(repo.segments("owner", record, archive).isFailure); assertEquals(count, requests.size)
        viewer = "owner"
        for (code in listOf(401, 403, 404, 503)) assertTrue(repository { code to "{}" }.segments("owner", record, archive).isFailure)
    }
    @Test fun missingRequiredFieldsAndMalformedSelectorsFailClosed() = runBlocking {
        val repo = repository { 200 to json(page).replace("\"timing_basis\":\"delivery\",", "") }
        assertTrue(repo.segments("owner", record, archive).isFailure)
        val count = requests.size
        assertTrue(repo.segments("owner", "not-uuid", archive).isFailure)
        assertTrue(repo.archives("owner", record, "x".repeat(2049)).isFailure)
        assertEquals(count, requests.size)
    }
}
