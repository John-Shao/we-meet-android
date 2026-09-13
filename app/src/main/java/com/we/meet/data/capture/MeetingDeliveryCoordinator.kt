package com.we.meet.data.capture

import com.squareup.moshi.JsonAdapter
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingDeliveryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Only explicit commands dispatch. Persist metadata/hash, never the rendered document copy. */
class MeetingDeliveryCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingDeliveryRepository) {
    private val lock = Mutex()
    suspend fun pending(kind: MeetingIntentKind, record: String): MeetingIntent? = withContext(Dispatchers.IO) {
        require(kind in kinds); store.get(kind, record)
    }
    suspend fun create(record: String, request: SummaryExportRequestDto): SummaryExportReceiptDto {
        MeetingDeliveryRepository.validate(request)
        return execute(MeetingIntentKind.DOCUMENT_EXPORT, record, request, MeetingDeliveryRepository.createAdapter) { key, original -> repository.create(viewer, record, key, original) }
    }
    suspend fun retryExport(record: String, request: SummaryExportRetryIntentDto): SummaryExportReceiptDto {
        MeetingDeliveryRepository.validate(request)
        return execute(MeetingIntentKind.DOCUMENT_EXPORT_RETRY, record, request, MeetingDeliveryRepository.exportRetryAdapter) { key, original -> repository.retryExport(viewer, record, key, original) }
    }
    suspend fun retryNotice(record: String, request: SummaryNoticeRetryIntentDto): SummaryNoticeReceiptDto {
        MeetingDeliveryRepository.validate(request)
        return execute(MeetingIntentKind.SUMMARY_NOTICE_RETRY, record, request, MeetingDeliveryRepository.noticeRetryAdapter) { key, original -> repository.retryNotice(viewer, record, key, original) }
    }
    private suspend fun <T, R> execute(kind: MeetingIntentKind, record: String, request: T, adapter: JsonAdapter<T>, send: suspend (String, T) -> Result<R>): R = withContext(Dispatchers.IO) {
        lock.withLock {
            val intent = store.getOrCreate(kind, record, adapter.toJson(request))
            try {
                val result = send(intent.key, requireNotNull(adapter.fromJson(intent.body))).getOrThrow()
                store.resolve(kind, record, intent); result
            } catch (error: HttpException) {
                // Access denial may follow a previously accepted request; retain its identity until access returns.
                if (error.code() in setOf(400, 409, 422)) store.resolve(kind, record, intent)
                throw error
            }
        }
    }
    companion object { val kinds = setOf(MeetingIntentKind.DOCUMENT_EXPORT, MeetingIntentKind.DOCUMENT_EXPORT_RETRY, MeetingIntentKind.SUMMARY_NOTICE_RETRY) }
}
