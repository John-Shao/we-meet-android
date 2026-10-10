package com.we.meet.ui.records

import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureDiarizationCoordinator
import com.we.meet.data.capture.MeetingIntent
import com.we.meet.data.repository.CaptureDiarizationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

internal interface DiarizationOperations {
    fun allowed(): Boolean
    suspend fun pending(): MeetingIntent?
    suspend fun read(): Result<CaptureDiarizationStateDto>
    suspend fun submit(revision: Int)
    suspend fun cancel(job: CaptureDiarizationJobDto, revision: Int)
    fun published()
}
internal class SessionDiarizationOperations(private val client: CaptureDiarizationSession,
    private val coordinator: CaptureDiarizationCoordinator, private val changed: () -> Unit) : DiarizationOperations {
    override fun allowed() = client.allowed()
    override suspend fun pending() = coordinator.pending()
    override suspend fun read() = client.state()
    override suspend fun submit(revision: Int) { coordinator.submit(revision) }
    override suspend fun cancel(job: CaptureDiarizationJobDto, revision: Int) { client.cancel(job, revision).getOrThrow() }
    override fun published() = changed()
}
internal enum class DiarizationFailure { READ, STORAGE, UNKNOWN, CONFLICT, DENIED, CANCEL }
internal data class DiarizationUiState(val data: CaptureDiarizationStateDto? = null, val pending: MeetingIntent? = null,
    val ready: Boolean = false, val busy: Boolean = false, val accepted: Boolean = false, val editing: Boolean = false,
    val failure: DiarizationFailure? = null) {
    val canSubmit get() = ready && !busy && !editing && data != null && (pending != null || accepted && data.canStart)
    val canCancel get() = !busy && data?.results?.firstOrNull()?.status in setOf("queued", "running")
    val shouldPoll get() = data != null && failure !in setOf(DiarizationFailure.READ, DiarizationFailure.STORAGE, DiarizationFailure.DENIED)
}

/** Reads never replay writes. Old private state disappears on access loss or disposal. */
internal class CaptureDiarizationController(private val operations: DiarizationOperations) {
    private val mutable = MutableStateFlow(DiarizationUiState())
    val state = mutable.asStateFlow()
    private var active = true
    private var published: String? = null
    private var hasPublished = false
    private fun live() = active && operations.allowed()
    fun close() { active = false; mutable.value = DiarizationUiState() }
    fun accept(value: Boolean) { if (live() && !state.value.busy && !state.value.editing && state.value.data?.canStart == true) mutable.value = state.value.copy(accepted = value) }
    fun editing(value: Boolean) { if (active) mutable.value = state.value.copy(editing = value, accepted = if (value) false else state.value.accepted) }
    private suspend fun read() {
        val previous = state.value.data
        val value = operations.read().getOrThrow()
        if (!live()) return
        mutable.value = state.value.copy(data = value, accepted = state.value.accepted &&
            previous?.recordRevision == value.recordRevision && previous?.sourceTranscriptionId == value.sourceTranscriptionId)
        if (!hasPublished || published != value.activeJobId) {
            hasPublished = true; published = value.activeJobId; operations.published()
        }
    }
    suspend fun refresh() {
        if (!live() || state.value.busy) return
        mutable.value = state.value.copy(busy = true)
        var loadedIntent = false
        try {
            val intent = operations.pending()
            loadedIntent = true
            if (!live()) return
            mutable.value = state.value.copy(pending = intent, ready = true)
            read()
            if (live() && state.value.failure in setOf(DiarizationFailure.READ, DiarizationFailure.STORAGE, DiarizationFailure.DENIED)) mutable.value = state.value.copy(failure = null)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live()) mutable.value = state.value.copy(data = null, ready = loadedIntent, accepted = false, failure = if (loadedIntent) DiarizationFailure.READ else DiarizationFailure.STORAGE) }
        finally { if (live()) mutable.value = state.value.copy(busy = false) else mutable.value = DiarizationUiState() }
    }
    suspend fun submit() {
        if (!live() || !state.value.canSubmit) return
        val revision = requireNotNull(state.value.data).recordRevision
        mutable.value = state.value.copy(busy = true, accepted = false, failure = null)
        try {
            operations.submit(revision)
            if (!live()) return
            mutable.value = state.value.copy(pending = null)
            read()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (!live()) return
            val status = (error as? HttpException)?.code()
            if (error is com.we.meet.data.capture.DiarizationIntentStorageException) {
                mutable.value = state.value.copy(ready = false, failure = DiarizationFailure.STORAGE)
                return
            }
            try {
                val intent = operations.pending()
                if (!live()) return
                mutable.value = state.value.copy(pending = intent, failure = when {
                    status == 409 -> DiarizationFailure.CONFLICT
                    status in setOf(400, 403, 404, 422, 503) -> DiarizationFailure.DENIED
                    else -> DiarizationFailure.UNKNOWN
                })
                if (status in setOf(401, 403, 404)) mutable.value = state.value.copy(data = null)
                else if (status in setOf(400, 409, 422, 503)) read()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (live()) mutable.value = state.value.copy(data = null, ready = false, failure = DiarizationFailure.STORAGE) }
        } finally { if (live()) mutable.value = state.value.copy(busy = false) else mutable.value = DiarizationUiState() }
    }
    suspend fun cancel() {
        if (!live() || !state.value.canCancel) return
        val data = requireNotNull(state.value.data)
        mutable.value = state.value.copy(busy = true, failure = null)
        try {
            operations.cancel(data.results.first(), data.recordRevision)
            if (live()) read()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (live()) mutable.value = if ((error as? HttpException)?.code() in setOf(401, 403, 404))
                state.value.copy(data = null, accepted = false, failure = DiarizationFailure.DENIED)
            else state.value.copy(failure = DiarizationFailure.CANCEL)
        }
        finally { if (live()) mutable.value = state.value.copy(busy = false) else mutable.value = DiarizationUiState() }
    }
}
