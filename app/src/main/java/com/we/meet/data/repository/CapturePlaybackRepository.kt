package com.we.meet.data.repository

import com.we.meet.data.api.CapturePlaybackApi
import com.we.meet.data.api.dto.CaptureAudioReceiptDto
import com.we.meet.data.api.dto.CaptureManifestDto
import com.we.meet.data.capture.CaptureWave
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CapturePlaylist(
    val recordId: String,
    val captureId: String,
    val captureRevision: Int,
    val manifest: CaptureManifestDto,
    val chunks: List<CaptureAudioReceiptDto>,
) {
    val endMs: Long get() = chunks.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0
    /** A missing interval is explicit; never jump to a different source position. */
    fun locate(milliseconds: Long): Int? = chunks.indexOfFirst { milliseconds >= it.startMs && milliseconds < it.startMs + it.durationMs }.takeIf { it >= 0 }
    override fun toString() = "CapturePlaylist(<private>)"
}

/** Owner-only sealed audio. No files, URL cache, large-body buffering or automatic retry. */
class CapturePlaybackRepository(
    private val captures: CaptureRepository,
    private val records: MeetingRecordRepository,
    private val api: CapturePlaybackApi,
    private val currentViewer: () -> String?,
) {
    suspend fun playlist(viewer: String, recordId: String): Result<CapturePlaylist> = scoped(viewer) {
        val record = records.record(viewer, recordId).getOrThrow()
        require(record.sourceType == "audio_recording" && record.capabilities.readTranscript && record.retentionMode == "media")
        val captureId = requireNotNull(record.captureId)
        val capture = captures.read(viewer, captureId).getOrThrow()
        require(capture.recordId == recordId && capture.status == "stopped")
        val chunks = mutableListOf<CaptureAudioReceiptDto>()
        var manifest: CaptureManifestDto? = null
        var after = 0
        var finished = false
        for (pageIndex in 0 until 44) {
            val page = captures.receipts(viewer, captureId, after).getOrThrow()
            val currentManifest = requireNotNull(page.manifest)
            if (manifest == null) manifest = currentManifest else require(manifest == currentManifest)
            page.results.filter { it.stored }.forEach {
                val previous = chunks.lastOrNull()
                require(it.sequence <= currentManifest.finalSequence && (previous == null ||
                    (it.sequence > previous.sequence && it.startMs >= previous.startMs + previous.durationMs)))
                require(chunks.none { old -> old.id == it.id })
                chunks += it
            }
            if (page.nextAfterSequence == null) { finished = true; break }
            after = page.nextAfterSequence
        }
        require(finished)
        val sealed = requireNotNull(manifest)
        val stored = chunks.map { it.sequence }.toSet()
        require((1..sealed.finalSequence).filter { it !in stored }.toSet() == sealed.missingSequences.toSet())
        require(chunks.sumOf { it.durationMs } == sealed.durationMs)
        require(sealed.outcome != "saved" || sealed.gaps.isEmpty())
        val playlist = CapturePlaylist(recordId, captureId, capture.revision, sealed, chunks.toList())
        verifyAccess(viewer, playlist)
        playlist
    }
    suspend fun checkAccess(viewer: String, playlist: CapturePlaylist): Result<Unit> = scoped(viewer) { verifyAccess(viewer, playlist) }

    private suspend fun verifyAccess(viewer: String, playlist: CapturePlaylist) {
        val record = records.record(viewer, playlist.recordId).getOrThrow()
        require(record.capabilities.readTranscript && record.sourceType == "audio_recording" && record.retentionMode == "media" && record.captureId == playlist.captureId)
        val capture = captures.read(viewer, playlist.captureId).getOrThrow()
        require(capture.recordId == record.id && capture.status == "stopped" && capture.revision == playlist.captureRevision)
        val access = captures.receipts(viewer, capture.id, CaptureWave.MAX_CHUNKS).getOrThrow()
        require(access.results.isEmpty() && access.nextAfterSequence == null && access.manifest == playlist.manifest)
    }
    suspend fun audio(viewer: String, playlist: CapturePlaylist, index: Int): Result<ByteArray> = scoped(viewer) {
        val receipt = playlist.chunks[index]
        require(receipt.stored && receipt.byteSize in 76..MAX_BYTES)
        require(UUID.fromString(receipt.id).toString() == receipt.id)
        verifyAccess(viewer, playlist)
        val bytes = ByteArray(receipt.byteSize)
        try {
            api.chunk(playlist.captureId, receipt.id).use { body ->
                val type = body.contentType()
                require(type?.type == "audio" && type.subtype in setOf("wav", "x-wav"))
                require(body.contentLength() == -1L || body.contentLength() == bytes.size.toLong())
                body.byteStream().use { input ->
                    var read = 0
                    while (read < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        require(currentViewer() == viewer)
                        val count = input.read(bytes, read, bytes.size - read)
                        require(count > 0)
                        read += count
                    }
                    require(input.read() == -1)
                }
            }
            val info = CaptureWave.inspect(bytes)
            require(info.checksum == receipt.checksum && info.durationMs == receipt.durationMs)
            verifyAccess(viewer, playlist)
            currentCoroutineContext().ensureActive()
            require(currentViewer() == viewer)
            bytes
        } catch (error: Throwable) { bytes.fill(0); throw error }
    }
    private suspend fun <T> scoped(viewer: String, operation: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            require(viewer.isNotBlank() && currentViewer() == viewer)
            val result = operation()
            require(currentViewer() == viewer)
            Result.success(result)
        } catch (canceled: CancellationException) { throw canceled }
        catch (error: Exception) { Result.failure(error) }
    }
    companion object { const val MAX_BYTES = 320044 }
}
