package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingReviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Encrypted original payload survives an unknown result; callers must explicitly reconcile it. */
class MeetingReviewCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingReviewRepository) {
    private val lock = Mutex()
    suspend fun pending(record: String, kind: MeetingIntentKind): MeetingIntent? = withContext(Dispatchers.IO) {
        require(kind in KINDS); store.get(kind, record)
    }
    suspend fun save(record: String, request: HumanReviewRequestDto): HumanReviewAcceptedDto {
        MeetingReviewRepository.validate(request)
        return execute(record, MeetingIntentKind.HUMAN_REVIEW, MeetingReviewRepository.reviewAdapter.toJson(request)) {
            repository.save(viewer, record, it.key, requireNotNull(MeetingReviewRepository.reviewAdapter.fromJson(it.body))).getOrThrow()
        }
    }
    suspend fun convert(record: String, request: SummaryTaskRequestDto): SummaryTaskAcceptedDto {
        MeetingReviewRepository.validate(request)
        return execute(record, MeetingIntentKind.SUMMARY_TASK, MeetingReviewRepository.taskAdapter.toJson(request)) {
            repository.convert(viewer, record, it.key, requireNotNull(MeetingReviewRepository.taskAdapter.fromJson(it.body))).getOrThrow()
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
    companion object { private val KINDS = setOf(MeetingIntentKind.HUMAN_REVIEW, MeetingIntentKind.SUMMARY_TASK) }
}
