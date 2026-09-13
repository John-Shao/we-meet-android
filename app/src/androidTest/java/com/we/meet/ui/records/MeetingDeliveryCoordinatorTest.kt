package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingDeliveryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingDeliveryRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MeetingDeliveryCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "delivery-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = SummaryExportRequestDto("human", UUID.randomUUID().toString(), "zh", "a".repeat(64))
    private val api = Fixture()
    private fun coordinator() = MeetingDeliveryCoordinator(viewer, store, MeetingDeliveryRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun unknownExportSurvivesRestartWithoutAutomaticDispatchAndKeepsFrozenHash() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().create(record, input) }.isFailure)
        val frozen = coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record)!!
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record)); assertEquals(1, api.sent.size)
        api.status = 200
        coordinator().create(record, input.copy(sourceId = record, language = "en"))
        assertEquals(api.sent.first(), api.sent.last()); assertNull(coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record))
    }
    @Test fun malformedSuccessAndPermissionLossRetainAlreadyUnknownIdentity() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().create(record, input) }.isFailure)
        val frozen = coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record)
        assertNotNull(frozen)
        api.invalid = false; api.status = 403
        assertTrue(runCatching { coordinator().create(record, input) }.isFailure)
        assertEquals(frozen, coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record))
        api.status = 200
        coordinator().create(record, input)
        assertEquals(1, api.sent.distinct().size)
    }
    @Test fun independentRetriesKeepExactTargetsAndNeverResolveAnotherKind() = runBlocking {
        val controller = coordinator()
        val exportRetry = SummaryExportRetryIntentDto(api.id, MeetingDeliveryRepository.selection(input), SummaryExportRetryDto(1, input.expectedHash))
        val noticeRetry = SummaryNoticeRetryIntentDto(UUID.randomUUID().toString(), input.sourceId, SummaryNoticeRetryDto(1))
        api.status = 408
        assertTrue(runCatching { controller.retryExport(record, exportRetry) }.isFailure)
        assertTrue(runCatching { controller.retryNotice(record, noticeRetry) }.isFailure)
        val first = api.sent.toList()
        api.status = 200
        controller.retryNotice(record, noticeRetry.copy(noticeId = record))
        assertNotNull(controller.pending(MeetingIntentKind.DOCUMENT_EXPORT_RETRY, record))
        controller.retryExport(record, exportRetry.copy(exportId = record))
        assertEquals(first[1], api.sent[2]); assertEquals(first[0], api.sent[3])
        assertNull(controller.pending(MeetingIntentKind.DOCUMENT_EXPORT_RETRY, record))
    }
    @Test fun definitiveConflictClearsOnlyExactKindAndInvalidInputCannotPoisonStore() = runBlocking {
        val other = store.getOrCreate(MeetingIntentKind.SUMMARY_REQUEST, record, "{}")
        assertTrue(runCatching { coordinator().create(record, input.copy(expectedHash = "bad")) }.isFailure)
        assertTrue(api.sent.isEmpty()); assertNull(coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record))
        api.status = 409
        assertTrue(runCatching { coordinator().create(record, input) }.isFailure)
        assertNull(coordinator().pending(MeetingIntentKind.DOCUMENT_EXPORT, record))
        assertEquals(other, store.get(MeetingIntentKind.SUMMARY_REQUEST, record))
    }
    private inner class Fixture : MeetingDeliveryApi {
        var status = 200
        var invalid = false
        val id = UUID.randomUUID().toString()
        val sent = mutableListOf<Triple<String, String, Any>>()
        private fun call(target: String, key: String, body: Any) {
            sent += Triple(target, key, body)
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
        }
        private fun row() = SummaryExportDto(id, "human", input.sourceId, "zh", "queued", 2, null, false, "", "2026-09-13T00:00:00Z")
        override suspend fun create(record: String, key: String, body: SummaryExportRequestDto): SummaryExportReceiptDto {
            call(record, key, body)
            return SummaryExportReceiptDto(row().copy(sourceId = if (invalid) record else body.sourceId, language = body.language), false)
        }
        override suspend fun retryExport(record: String, export: String, key: String, body: SummaryExportRetryDto): SummaryExportReceiptDto {
            call(export, key, body); return SummaryExportReceiptDto(row().copy(id = export), true)
        }
        override suspend fun retryNotice(record: String, notice: String, key: String, body: SummaryNoticeRetryDto): SummaryNoticeReceiptDto {
            call(notice, key, body); return SummaryNoticeReceiptDto(SummaryNoticeDto(notice, input.sourceId, "queued", 2, "", row().createdAt), true)
        }
        override suspend fun exports(record: String): SummaryExportsDto = error("No automatic reads")
        override suspend fun preview(record: String, kind: String, source: String, language: String): SummaryExportPreviewDto = error("No automatic reads")
        override suspend fun retryPreview(record: String, export: String): SummaryExportRetryPreviewDto = error("No automatic reads")
        override suspend fun notices(record: String): SummaryNoticesDto = error("No automatic reads")
    }
}
