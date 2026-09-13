package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingQuestionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** One unresolved question per record; app recreation and GETs never dispatch provider requests. */
class MeetingQuestionCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingQuestionRepository) {
    private val lock = Mutex()
    suspend fun pending(record: String): MeetingIntent? = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.RECORD_QUESTION, record) }
    suspend fun ask(record: String, request: RecordQuestionRequestDto): RecordQuestionDto {
        MeetingQuestionRepository.validate(request)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val intent = store.getOrCreate(MeetingIntentKind.RECORD_QUESTION, record, MeetingQuestionRepository.requestAdapter.toJson(request))
                try {
                    val result = repository.ask(viewer, record, intent.key, requireNotNull(MeetingQuestionRepository.requestAdapter.fromJson(intent.body))).getOrThrow()
                    store.resolve(MeetingIntentKind.RECORD_QUESTION, record, intent)
                    result
                } catch (error: HttpException) {
                    if (error.code() in setOf(400, 401, 403, 404, 409, 422)) store.resolve(MeetingIntentKind.RECORD_QUESTION, record, intent)
                    throw error
                }
            }
        }
    }
}
