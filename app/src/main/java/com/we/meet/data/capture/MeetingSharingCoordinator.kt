package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSharingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Persist the confirmed selection/hash before applying; replays return the original receipt, never regrant. */
class MeetingSharingCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingSharingRepository) {
    private val lock = Mutex()
    suspend fun pending(record: String): MeetingIntent? = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.SUMMARY_SHARE, record) }
    suspend fun apply(record: String, request: SummaryShareRequestDto): SummaryShareReceiptDto {
        MeetingSharingRepository.validate(request)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val intent = store.getOrCreate(MeetingIntentKind.SUMMARY_SHARE, record, MeetingSharingRepository.requestAdapter.toJson(request))
                try {
                    val result = repository.apply(viewer, record, intent.key, requireNotNull(MeetingSharingRepository.requestAdapter.fromJson(intent.body))).getOrThrow()
                    store.resolve(MeetingIntentKind.SUMMARY_SHARE, record, intent); result
                } catch (error: HttpException) {
                    if (error.code() in setOf(400, 409, 422)) store.resolve(MeetingIntentKind.SUMMARY_SHARE, record, intent)
                    throw error
                }
            }
        }
    }
}
