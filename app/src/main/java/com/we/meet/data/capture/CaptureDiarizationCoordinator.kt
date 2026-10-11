package com.we.meet.data.capture

import com.squareup.moshi.Moshi
import com.squareup.moshi.Json
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.CaptureDiarizationRequestDto
import com.we.meet.data.repository.CaptureDiarizationSession
import com.we.meet.data.repository.IdentityLoginChangedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

interface DiarizationIntentPersistence {
    fun get(): MeetingIntent?
    fun remember(body: String): MeetingIntent
    fun resolve(intent: MeetingIntent)
}
class StoredDiarizationIntent(private val store: MeetingIntentStore, private val capture: String) : DiarizationIntentPersistence {
    override fun get() = store.get(MeetingIntentKind.CAPTURE_DIARIZATION, capture)
    override fun remember(body: String) = store.getOrCreate(MeetingIntentKind.CAPTURE_DIARIZATION, capture, body)
    override fun resolve(intent: MeetingIntent) = store.resolve(MeetingIntentKind.CAPTURE_DIARIZATION, capture, intent)
}
private data class SavedDiarizationRequest(val session: String, @Json(name = "expected_revision") val expectedRevision: Int)
class DiarizationIntentStorageException : IllegalStateException("capture_diarization_intent_storage_failed")

/** Encrypted durable nonce/revision; only an explicit action can replay, with the original body. */
class CaptureDiarizationCoordinator(private val client: CaptureDiarizationSession, private val store: DiarizationIntentPersistence) {
    private val lock = Mutex()
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(SavedDiarizationRequest::class.java)
    private fun guard() { if (!client.allowed()) throw IdentityLoginChangedException() }
    private fun decode(intent: MeetingIntent): SavedDiarizationRequest = requireNotNull(adapter.fromJson(intent.body)).also {
        require(it.expectedRevision > 0 && it.session.length in 1..256)
    }
    private fun load(): MeetingIntent? {
        guard()
        val value = store.get() ?: return null
        val body = decode(value)
        if (body.session != client.session) { store.resolve(value); return null }
        return value
    }
    suspend fun pending() = withContext(Dispatchers.IO) { lock.withLock { load() } }
    suspend fun submit(revision: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            guard(); require(revision > 0)
            val intent = load() ?: try { store.remember(adapter.toJson(SavedDiarizationRequest(client.session, revision))) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { throw DiarizationIntentStorageException() }
            val request = decode(intent)
            guard()
            try {
                val result = client.submit(intent.key, CaptureDiarizationRequestDto(request.expectedRevision)).getOrThrow()
                guard(); store.resolve(intent); result
            } catch (error: HttpException) {
                if (error.code() in setOf(400, 409, 422, 503)) { guard(); store.resolve(intent) }
                throw error
            }
        }
    }
}
