package com.we.meet.data.capture

import com.we.meet.data.api.dto.CaptureCommandDto
import com.we.meet.data.api.dto.SealCaptureDto
import com.we.meet.data.repository.CaptureRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** One service owns this coordinator. Hardware must stop and drain before pause/finish.
 * Loading never opens hardware or makes a network request. Explicit actions may replay only
 * persisted intents; a failed action is returned to the caller, never retried in a loop.
 */
class CaptureRecovery(
    private val viewer: String,
    private val journal: CaptureJournal,
    private val repository: CaptureRepository,
) {
    private val control = Mutex()
    private val uploads = Mutex()
    private val closeEpoch = AtomicLong()

    suspend fun load(): List<LocalCapture> = action {
        journal.list().map { row ->
            if (!row.closed && !row.sealed) journal.update(row.id) { it.copy(closed = true, interrupted = true) }
            else row
        }
    }

    suspend fun prepare(title: String, retentionMode: String = "media"): LocalCapture = action {
        if (retentionMode == "text") check(repository.textAudioAvailable(viewer).getOrThrow()) { "Text audio unavailable" }
        journal.create(title, retentionMode)
    }

    /** Call only after foreground microphone permission has been obtained; returns start authority. */
    suspend fun start(id: String): LocalCapture = action {
        val epoch = closeEpoch.get()
        val local = journal.get(id)
        check(local.closed && !local.sealed && local.sealIntent == null)
        if (local.create.retentionMode == "text") {
            check(!CaptureRetention.audioExpired(local)) { "Temporary audio expired" }
            check(repository.textAudioAvailable(viewer).getOrThrow()) { "Text audio unavailable" }
        }
        reconcile(id)
        if (journal.get(id).remote!!.status == "recording") {
            // A previous process may have lost the start response or its final hardware tail.
            journal.update(id) { it.copy(interrupted = true) }
            command(id, "interrupt")
        }
        when (journal.get(id).remote!!.status) {
            "preparing" -> command(id, "start")
            "paused", "interrupted" -> command(id, "resume")
            else -> error("Recording has ended")
        }
        check(journal.get(id).remote!!.status == "recording")
        journal.update(id) {
            check(closeEpoch.get() == epoch) { "Recording was closed while starting" }
            it.copy(closed = false)
        }
    }

    /** Local close is durable even if the following server operation fails. */
    suspend fun pause(id: String, interrupted: Boolean = false): LocalCapture = action {
        journal.update(id) { it.copy(closed = true, interrupted = it.interrupted || interrupted) }
        reconcile(id)
        if (journal.get(id).remote!!.status == "recording") command(id, if (interrupted) "interrupt" else "pause")
        journal.get(id)
    }

    /** Called only after hardware has released the microphone, including unexpected shutdown. */
    suspend fun closeLocally(id: String, interrupted: Boolean): LocalCapture {
        closeEpoch.incrementAndGet()
        return withContext(Dispatchers.IO) {
            journal.update(id) { it.copy(closed = true, interrupted = it.interrupted || interrupted) }
        }
    }

    suspend fun retryUploads(id: String): LocalCapture = action {
        reconcile(id)
        uploadPending(id)
        journal.get(id)
    }

    /** Independent of the control mutex so a slow HTTP call cannot block PCM persistence. */
    suspend fun uploadPending(id: String): LocalCapture = withContext(Dispatchers.IO) {
        uploads.withLock {
            val local = journal.get(id)
            if (local.sealed || local.remote == null) return@withLock local
            for (chunk in journal.chunks(id)) {
                if (chunk.receipt != null) continue
                val current = journal.get(id)
                check(current.sealIntent == null) { "Recover sealed delivery before uploading" }
                val audio = journal.audio(id, chunk.sequence)
                val receipt = try {
                    repository.upload(viewer, current.remote!!.id, current.create.leaseKey, current.create.deviceId,
                        chunk.sequence, chunk.startMs, chunk.checksum, audio).getOrThrow()
                } finally { audio.fill(0) }
                journal.acknowledge(id, receipt)
            }
            journal.get(id)
        }
    }

    suspend fun finish(id: String, allowMissing: Boolean = false): LocalCapture = action {
        val initial = journal.get(id)
        if (initial.sealed) return@action initial
        val partial = initial.allowMissingAudio || (allowMissing && initial.sealIntent == null)
        require(!(allowMissing || partial) || initial.create.retentionMode == "text")
        journal.update(id) { it.copy(closed = true) }
        reconcile(id)
        if (journal.get(id).remote!!.status !in setOf("stopping", "stopped")) command(id, "stop")
        if (!partial) uploadPending(id)
        else uploads.withLock { journal.discardTextAudio(id) }
        val local = journal.get(id)
        check(local.pendingBytes == 0)
        val frozen = journal.update(id) {
            it.copy(sealIntent = it.sealIntent ?: SealCaptureDto(it.create.deviceId, it.nextSequence - 1, it.interrupted || partial),
                allowMissingAudio = partial)
        }
        val manifest = repository.seal(viewer, frozen.remote!!.id, frozen.create.leaseKey, frozen.sealIntent!!).getOrThrow()
        // Only a persisted explicit text-mode choice may accept missing source samples.
        if (partial) check(manifest.durationMs <= frozen.durationMs)
        else check(manifest.durationMs == frozen.durationMs && manifest.missingSequences.isEmpty() && manifest.gaps.isEmpty())
        if (journal.get(id).remote!!.status != "stopped") command(id, "finalize")
        journal.update(id) { it.copy(sealed = true) }
    }

    private suspend fun reconcile(id: String) {
        var local = journal.get(id)
        if (local.remote == null) {
            val response = repository.create(viewer, local.createKey, local.create).getOrThrow()
            journal.update(id) { it.copy(remote = response.capture) }
        }
        pendingCommand(id)
        local = journal.get(id)
        val remote = repository.read(viewer, local.remote!!.id).getOrThrow()
        journal.update(id) { it.copy(remote = remote) }
        // Serialize receipt recovery with in-flight uploads; late exact receipts are idempotent.
        uploads.withLock {
            val chunks = journal.chunks(id).associateBy { it.sequence }
            if (chunks.isEmpty()) return@withLock
            var after = 0
            repeat(44) {
                val page = repository.receipts(viewer, remote.id, after).getOrThrow()
                page.results.forEach { receipt ->
                    check(chunks.containsKey(receipt.sequence)) { "Remote audio is outside this device journal" }
                    if (receipt.stored) journal.acknowledge(id, receipt)
                }
                val next = page.nextAfterSequence ?: return@withLock
                check(next > after)
                after = next
            }
            error("Audio receipt pagination exceeded its bound")
        }
    }

    private suspend fun command(id: String, name: String) {
        pendingCommand(id)
        journal.update(id) {
            it.copy(command = PendingCaptureCommand(UUID.randomUUID().toString(),
                CaptureCommandDto(name, it.create.deviceId, it.remote!!.revision)))
        }
        pendingCommand(id)
    }

    private suspend fun pendingCommand(id: String) {
        val local = journal.get(id)
        val intent = local.command ?: return
        try {
            val response = repository.command(viewer, local.remote!!.id, intent.key, local.create.leaseKey, intent.body).getOrThrow()
            journal.update(id) { it.copy(remote = response.capture, command = null) }
        } catch (error: HttpException) {
            // The backend checks idempotent replay before CAS. A 409 rejected this transition;
            // fail this action, refresh on the next user action, never silently issue a new key.
            if (error.code() == 409) journal.update(id) { it.copy(command = null) }
            throw error
        }
    }

    private suspend fun <T> action(run: suspend () -> T): T = withContext(Dispatchers.IO) { control.withLock { run() } }
}
