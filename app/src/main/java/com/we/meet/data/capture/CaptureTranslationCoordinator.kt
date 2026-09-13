package com.we.meet.data.capture

import com.we.meet.data.api.dto.CaptureTranslationRequestDto
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Saves metadata only. Explicit recovery never requests a ticket or reconnects audio. */
class CaptureTranslationCoordinator(private val source: CaptureTranslationSource, private val store: MeetingIntentStore, private val repository: CaptureTranslationRepository) {
    private val lock = Mutex()
    suspend fun pending() = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.CAPTURE_TRANSLATION, source.capture) }
    suspend fun control(request: CaptureTranslationRequestDto) = withContext(Dispatchers.IO) {
        CaptureTranslationRepository.validateSource(source)
        CaptureTranslationRepository.validate(request, source.device)
        lock.withLock {
            val kind = MeetingIntentKind.CAPTURE_TRANSLATION
            val intent = store.getOrCreate(kind, source.capture, CaptureTranslationRepository.requestAdapter.toJson(request))
            try {
                val original = requireNotNull(CaptureTranslationRepository.requestAdapter.fromJson(intent.body))
                CaptureTranslationRepository.validate(original, source.device)
                val receipt = repository.control(source, intent.key, original).getOrThrow()
                store.resolve(kind, source.capture, intent)
                receipt
            } catch (error: HttpException) {
                if (error.code() in setOf(400, 409, 422)) store.resolve(kind, source.capture, intent)
                throw error
            }
        }
    }
}
