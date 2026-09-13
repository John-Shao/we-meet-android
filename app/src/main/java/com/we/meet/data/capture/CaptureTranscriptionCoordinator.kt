package com.we.meet.data.capture

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.CaptureAsrCreatedDto
import com.we.meet.data.api.dto.CaptureAsrRequestDto
import com.we.meet.data.repository.CaptureTranscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Explicit paid request only. Loading or polling cannot dispatch a stored request. */
class CaptureTranscriptionCoordinator(
    private val viewer: String,
    private val store: MeetingIntentStore,
    private val repository: CaptureTranscriptionRepository,
) {
    private val lock = Mutex()
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(CaptureAsrRequestDto::class.java).serializeNulls()

    suspend fun pending(capture: String): MeetingIntent? = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.CAPTURE_ASR, capture) }

    suspend fun submit(capture: String, request: CaptureAsrRequestDto): CaptureAsrCreatedDto = withContext(Dispatchers.IO) {
        lock.withLock {
            CaptureTranscriptionRepository.validate(request)
            val intent = store.getOrCreate(MeetingIntentKind.CAPTURE_ASR, capture, adapter.toJson(request))
            val body = requireNotNull(adapter.fromJson(intent.body))
            try {
                val result = repository.request(viewer, capture, intent.key, body).getOrThrow()
                store.resolve(MeetingIntentKind.CAPTURE_ASR, capture, intent)
                result
            } catch (error: HttpException) {
                // Access loss cannot establish the outcome of an earlier attempt. Preserve its key/body.
                // Timeout, throttling and server errors remain uncertain. A definitive rejection
                // clears this exact intent but still fails the user action; no implicit retry.
                if (error.code() in setOf(400, 409, 422)) store.resolve(MeetingIntentKind.CAPTURE_ASR, capture, intent)
                throw error
            }
        }
    }
}
