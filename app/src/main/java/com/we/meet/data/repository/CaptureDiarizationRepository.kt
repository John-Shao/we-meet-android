package com.we.meet.data.repository

import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Every read/write is fixed to the owner and actual login, including late HTTP results. */
class CaptureDiarizationRepository(private val api: CaptureTranscriptionApi,
    private val currentViewer: () -> String?, private val currentSession: () -> String) {
    fun open(viewer: String, capture: String): CaptureDiarizationSession {
        identityUuid(viewer); identityUuid(capture); require(viewer == currentViewer())
        return CaptureDiarizationSession(api, viewer, capture, currentSession(), currentViewer, currentSession)
    }
}

class CaptureDiarizationSession internal constructor(private val api: CaptureTranscriptionApi,
    val viewer: String, val capture: String, val session: String,
    private val currentViewer: () -> String?, private val currentSession: () -> String) {
    fun allowed() = currentViewer() == viewer && currentSession() == session
    private suspend fun <T> read(operation: suspend (PrivateLogin) -> T): Result<T> = try {
        if (!allowed()) throw IdentityLoginChangedException()
        val result = withTimeout(15_000) { operation(PrivateLogin(session)) }
        if (!allowed()) throw IdentityLoginChangedException()
        Result.success(result)
    } catch (_: TimeoutCancellationException) { Result.failure(IdentityRequestTimeoutException()) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }

    suspend fun state(): Result<CaptureDiarizationStateDto> = read { login ->
        api.diarizationState(capture, viewer, login).also { value ->
            require(value.recordRevision > 0 && value.results.size <= 20)
            require(!value.canStart || value.available && value.sourceTranscriptionId != null)
            value.sourceTranscriptionId?.let(::identityUuid); value.activeJobId?.let(::identityUuid)
            require(value.results.map { it.id }.distinct().size == value.results.size)
            var generation = Int.MAX_VALUE
            value.results.forEach { validateDiarizationJob(it); require(it.generation < generation); generation = it.generation }
            value.results.find { it.id == value.activeJobId }?.let {
                require(it.status == "succeeded" && it.sourceTranscriptionId == value.sourceTranscriptionId && it.publishedCount > 0)
            }
            require(value.activeJobId == null || value.sourceTranscriptionId != null)
        }
    }
    suspend fun submit(key: String, body: CaptureDiarizationRequestDto) = read { login ->
        identityUuid(key); require(body.expectedRevision > 0)
        api.requestDiarization(capture, viewer, login, key, body).also {
            validateDiarizationJob(it.job)
            require(it.job.sourceRevision == body.expectedRevision && it.commandReceipt.key == key &&
                it.commandReceipt.scope == mapOf("capture_id" to capture))
        }
    }
    suspend fun cancel(job: CaptureDiarizationJobDto, revision: Int) = read { login ->
        validateDiarizationJob(job); require(revision > 0)
        api.cancelDiarization(capture, job.id, viewer, login, CaptureDiarizationRequestDto(revision)).also {
            validateDiarizationJob(it.job); require(it.job.id == job.id && it.job.status == "canceled")
        }
    }
}

internal fun validateDiarizationJob(value: CaptureDiarizationJobDto) {
    identityUuid(value.id); identityUuid(value.sourceTranscriptionId)
    require(value.generation > 0 && value.sourceRevision > 0 && value.publishedCount in 0..20000 &&
        value.status in setOf("queued", "running", "succeeded", "failed", "canceled"))
}
