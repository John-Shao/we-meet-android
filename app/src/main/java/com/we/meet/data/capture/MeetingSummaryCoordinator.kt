package com.we.meet.data.capture

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSummaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Persistence precedes billable effects; UI recreation and status reads never dispatch. */
class MeetingSummaryCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingSummaryRepository) {
    private val lock = Mutex()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val summaryAdapter = moshi.adapter(SummaryRequestDto::class.java).serializeNulls()
    private val automationAdapter = moshi.adapter(SummaryAutomationRequestDto::class.java)

    suspend fun pending(record: String, kind: MeetingIntentKind): MeetingIntent? = withContext(Dispatchers.IO) {
        require(kind in KINDS)
        store.get(kind, record)
    }
    suspend fun submit(record: String, request: SummaryRequestDto): SummaryAcceptedDto {
        MeetingSummaryRepository.validate(request)
        return execute(record, MeetingIntentKind.SUMMARY_REQUEST, summaryAdapter.toJson(request)) {
            repository.request(viewer, record, it.key, requireNotNull(summaryAdapter.fromJson(it.body))).getOrThrow()
        }
    }
    suspend fun control(record: String, request: SummaryAutomationRequestDto): SummaryAutomationAcceptedDto {
        MeetingSummaryRepository.validate(request)
        return execute(record, MeetingIntentKind.SUMMARY_AUTOMATION, automationAdapter.toJson(request)) {
            repository.control(viewer, record, it.key, requireNotNull(automationAdapter.fromJson(it.body))).getOrThrow()
        }
    }
    private suspend fun <T> execute(record: String, kind: MeetingIntentKind, body: String, send: suspend (MeetingIntent) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val intent = store.getOrCreate(kind, record, body)
            try {
                val result = send(intent)
                store.resolve(kind, record, intent)
                result
            } catch (error: HttpException) {
                if (error.code() in setOf(400, 401, 403, 404, 409, 422)) store.resolve(kind, record, intent)
                throw error
            }
        }
    }
    companion object { private val KINDS = setOf(MeetingIntentKind.SUMMARY_REQUEST, MeetingIntentKind.SUMMARY_AUTOMATION) }
}
