package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingDeliveryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingDeliveryRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class MeetingDeliveryRepositoryTest {
    private val record = UUID.randomUUID().toString()
    private val source = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val key = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val selection = SummaryExportSelectionDto("human", source, "zh")
    private val input = SummaryExportRequestDto("human", source, "zh", hash)
    private val row = SummaryExportDto(id, "human", source, "zh", "queued", 1, null, false, "", "2026-09-13T00:00:00Z")
    private val notice = SummaryNoticeDto(id, source, "queued", 2, "", row.createdAt)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private inline fun <reified T> json(value: T) = moshi.adapter(T::class.java).serializeNulls().toJson(value)
    private fun repository(reply: (Request) -> Pair<Int, String>): MeetingDeliveryRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            assertEquals("meeting.invalid", request.url.host); assertEquals("no-store", request.header("Cache-Control"))
            val (code, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return MeetingDeliveryRepository(Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(MeetingDeliveryApi::class.java)) { viewer }
    }
    @Test fun previewUsesExactSelectionAndCreateSendsOnlyReviewedHashAndHeaderKey() = runBlocking {
        val repo = repository {
            if (it.method == "GET") {
                assertTrue(it.url.encodedPath.endsWith("/document-exports/preview/"))
                assertEquals(source, it.url.queryParameter("source_id")); assertEquals("human", it.url.queryParameter("source_kind"))
                200 to json(SummaryExportPreviewDto("Title", "# Content", hash, "human", source, "zh"))
            } else {
                assertEquals("/api/v1.0/meeting-records/$record/document-exports/", it.url.encodedPath)
                assertEquals(key, it.header("Idempotency-Key"))
                val body = Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()
                assertEquals(input, MeetingDeliveryRepository.createAdapter.fromJson(body)); assertFalse(body.contains("markdown"))
                202 to json(SummaryExportReceiptDto(row, false))
            }
        }
        assertEquals(hash, repo.preview("owner", record, selection).getOrThrow().payloadHash)
        assertEquals(1, requests.size)
        assertEquals(row, repo.create("owner", record, key, input).getOrThrow().export)
    }
    @Test fun malformedSuccessAndMismatchedSelectionNeverAcknowledgeCreate() = runBlocking {
        assertTrue(repository { 202 to "{}" }.create("owner", record, key, input).isFailure)
        assertTrue(repository { 202 to json(SummaryExportReceiptDto(row.copy(sourceId = record), false)) }.create("owner", record, key, input).isFailure)
        assertTrue(repository { 202 to json(SummaryExportReceiptDto(row.copy(language = "en"), false)) }.create("owner", record, key, input).isFailure)
    }
    @Test fun previewRejectsWrongVersionOrInvalidHashOrOversizedUtf8() = runBlocking {
        val preview = SummaryExportPreviewDto("Title", "# Content", hash, "human", source, "zh")
        for (bad in listOf(preview.copy(sourceId = record), preview.copy(payloadHash = "wrong"), preview.copy(markdown = "中".repeat(700_000)))) {
            assertTrue(repository { 200 to json(bad) }.preview("owner", record, selection).isFailure)
        }
    }
    @Test fun exportRetryAllowsAdvancedReplayButRequiresOriginalExportAndLaterAttempt() = runBlocking {
        val intent = SummaryExportRetryIntentDto(id, selection, SummaryExportRetryDto(1, hash))
        val repo = repository {
            assertEquals("/api/v1.0/meeting-records/$record/document-exports/$id/retry/", it.url.encodedPath)
            assertEquals(key, it.header("Idempotency-Key"))
            202 to json(SummaryExportReceiptDto(row.copy(attempt = 4), true))
        }
        assertEquals(4, repo.retryExport("owner", record, key, intent).getOrThrow().export.attempt)
        assertTrue(repository { 202 to json(SummaryExportReceiptDto(row, true)) }.retryExport("owner", record, key, intent).isFailure)
        assertTrue(repository { 202 to json(SummaryExportReceiptDto(row.copy(id = source, attempt = 2), false)) }.retryExport("owner", record, key, intent).isFailure)
    }
    @Test fun frozenRetryPreviewRequiresExactExportAndReadyDocumentCannotBeMissing() = runBlocking {
        assertTrue(repository { 200 to json(SummaryExportRetryPreviewDto(row, "Title", "Content", hash)) }.retryPreview("owner", record, id).isSuccess)
        assertTrue(repository { 200 to json(SummaryExportRetryPreviewDto(row.copy(id = record), "Title", "Content", hash)) }.retryPreview("owner", record, id).isFailure)
        for (bad in listOf(row.copy(status = "ready"), row.copy(canOpen = true), row.copy(documentId = "https://evil.invalid"))) {
            assertTrue(repository { 200 to json(SummaryExportsDto(true, listOf(bad))) }.exports("owner", record).isFailure)
        }
    }
    @Test fun notificationsUseOwnReceiptAndKeepGenerationAndDeliveryStatusesSeparate() = runBlocking {
        val input = SummaryNoticeRetryIntentDto(id, source, SummaryNoticeRetryDto(1))
        val repo = repository {
            assertTrue(it.url.encodedPath.endsWith("/summary-notifications/$id/retry/")); assertEquals(key, it.header("Idempotency-Key"))
            202 to json(SummaryNoticeReceiptDto(notice.copy(status = "delivered", attempt = 3), true))
        }
        assertEquals("delivered", repo.retryNotice("owner", record, key, input).getOrThrow().notification.status)
        for (bad in listOf(notice.copy(summaryId = record), notice.copy(status = "succeeded"), notice.copy(attempt = 1))) {
            assertTrue(repository { 202 to json(SummaryNoticeReceiptDto(bad, false)) }.retryNotice("owner", record, key, input).isFailure)
        }
    }
    @Test fun readCollectionsAreBoundedAndPolicyIsValidated() = runBlocking {
        assertFalse(repository { 200 to "{}" }.exports("owner", record).getOrThrow().available)
        assertTrue(repository { 200 to json(SummaryExportsDto(true, listOf(row, row))) }.exports("owner", record).isFailure)
        val valid = SummaryNoticesDto(true, "owner", true, listOf(notice), listOf(SummaryNoticeRecipientDto(source, "Owner")))
        assertTrue(repository { 200 to json(valid) }.notices("owner", record).isSuccess)
        for (bad in listOf(valid.copy(strategy = "all_attendees"), valid.copy(legacyDeliveryUnchanged = false), valid.copy(results = listOf(notice, notice)), valid.copy(futureRecipients = List(101) { SummaryNoticeRecipientDto(UUID.randomUUID().toString(), "Owner") }))) {
            assertTrue(repository { 200 to json(bad) }.notices("owner", record).isFailure)
        }
    }
    @Test fun invalidSelectionsAndExhaustedAttemptsDoNotDispatch() = runBlocking {
        val repo = repository { error("No dispatch") }
        assertTrue(repo.create("owner", record, key, input.copy(expectedHash = "invalid")).isFailure)
        assertTrue(repo.preview("owner", record, selection.copy(sourceId = "latest")).isFailure)
        assertTrue(repo.retryNotice("owner", record, key, SummaryNoticeRetryIntentDto(id, source, SummaryNoticeRetryDto(20))).isFailure)
        assertTrue(requests.isEmpty())
    }
    @Test fun privateDeliveryResultIsDroppedOnAccountChange() = runBlocking {
        assertTrue(repository { viewer = null; 200 to json(SummaryExportsDto(true, listOf(row))) }.exports("owner", record).isFailure)
    }
}
