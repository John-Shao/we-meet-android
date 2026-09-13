package com.we.meet.data.repository

import com.we.meet.data.api.CaptureApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.capture.CaptureRetention
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Caller persists keys and bodies before writing; unknown outcomes never create replacement keys here. */
class CaptureRepository(private val api: CaptureApi, private val currentViewer: () -> String?) {
    suspend fun textAudioAvailable(viewer: String): Result<Boolean> = scoped(viewer) {
        api.audioCapabilities().let {
            require(it.textAudioError in setOf("", "rollout_disabled", "storage_unavailable", "versioned_storage_requires_purge", "unsupported_storage"))
            require(it.textAudioAvailable == it.textAudioError.isEmpty())
            it.textAudioAvailable
        }
    }

    suspend fun create(viewer: String, key: String, request: CreateCaptureDto): Result<CaptureOperationDto> = scoped(viewer) {
        uuid(key); uuid(request.leaseKey, random = true); device(request.deviceId)
        require(request.title.length <= 500 && request.retentionMode in setOf("media", "text"))
        api.create(key, request).also {
            operation(it, null, request.deviceId)
            if (request.retentionMode == "text") require(it.result.audioRetention?.mode == "text" && it.capture.audioRetention?.mode == "text")
        }
    }

    suspend fun read(viewer: String, captureId: String): Result<CaptureDto> = scoped(viewer) {
        uuid(captureId)
        api.read(captureId).also { state(it); require(it.id == captureId) }
    }

    suspend fun command(viewer: String, captureId: String, key: String, lease: String, request: CaptureCommandDto): Result<CaptureOperationDto> = scoped(viewer) {
        uuid(captureId); uuid(key); uuid(lease, random = true); device(request.deviceId)
        require(request.expectedRevision > 0 && request.command in setOf("start", "pause", "resume", "interrupt", "stop", "finalize"))
        api.command(captureId, key, lease, request).also { operation(it, captureId, request.deviceId) }
    }

    suspend fun receipts(viewer: String, captureId: String, after: Int = 0): Result<CaptureReceiptsDto> = scoped(viewer) {
        uuid(captureId); require(after in 0..CaptureWave.MAX_CHUNKS)
        api.receipts(captureId, after).also { page ->
            require(page.results.size <= 100)
            var last = after
            page.results.forEach { receipt(it); require(it.sequence > last); last = it.sequence }
            require(page.nextAfterSequence == null || (page.results.isNotEmpty() && page.nextAfterSequence == last))
            page.manifest?.let(::manifest)
        }
    }

    suspend fun upload(viewer: String, captureId: String, lease: String, deviceId: String,
        sequence: Int, startMs: Long, checksum: String, audio: ByteArray): Result<CaptureAudioReceiptDto> = scoped(viewer) {
        uuid(captureId); uuid(lease, random = true); device(deviceId)
        require(sequence in 1..CaptureWave.MAX_CHUNKS && startMs >= 0)
        val frozen = audio.copyOf()
        val info = CaptureWave.inspect(frozen)
        require(info.checksum == checksum && startMs <= CaptureWave.MAX_DURATION_MS - info.durationMs)
        val fields = mapOf("device_id" to deviceId, "sequence" to sequence.toString(), "start_ms" to startMs.toString(), "checksum" to checksum)
            .mapValues { it.value.toRequestBody("text/plain".toMediaType()) }
        val part = MultipartBody.Part.createFormData("audio", "chunk.wav", frozen.toRequestBody("audio/wav".toMediaType()))
        try { api.upload(captureId, lease, fields, part).also {
            receipt(it)
            require(it.stored && it.sequence == sequence && it.startMs == startMs && it.checksum == checksum &&
                it.durationMs == info.durationMs && it.byteSize == info.byteSize)
        } } finally { frozen.fill(0) }
    }

    suspend fun seal(viewer: String, captureId: String, lease: String, request: SealCaptureDto): Result<CaptureManifestDto> = scoped(viewer) {
        uuid(captureId); uuid(lease, random = true); device(request.deviceId)
        require(request.finalSequence in 0..CaptureWave.MAX_CHUNKS)
        api.seal(captureId, lease, request).also {
            manifest(it)
            require(it.finalSequence == request.finalSequence)
            require(!request.clientInterrupted || it.outcome == "incomplete")
        }
    }

    private fun operation(value: CaptureOperationDto, id: String?, deviceId: String) {
        uuid(value.operationId); state(value.result); state(value.capture)
        require(id == null || value.capture.id == id)
        require(value.result.id == value.capture.id && value.result.recordId == value.capture.recordId)
        require(value.result.deviceId == deviceId && value.capture.deviceId == deviceId)
        require(value.result.revision <= value.capture.revision)
    }

    private fun state(value: CaptureDto) {
        value.audioRetention?.let(CaptureRetention::validate)
        uuid(value.id); uuid(value.recordId); device(value.deviceId)
        require(value.revision > 0 && value.lastAckedSequence in 0..CaptureWave.MAX_CHUNKS)
        require(value.status in setOf("preparing", "recording", "paused", "interrupted", "stopping", "stopped"))
        require(value.mediaStatus in setOf("not_connected", "uploading", "saved", "incomplete", "empty"))
        require(value.capturedDurationMs == null || value.capturedDurationMs in 0..CaptureWave.MAX_DURATION_MS)
    }

    private fun receipt(value: CaptureAudioReceiptDto) {
        uuid(value.id)
        require(value.sequence in 1..CaptureWave.MAX_CHUNKS && value.durationMs in 1..10000)
        require(value.startMs >= 0 && value.startMs <= CaptureWave.MAX_DURATION_MS - value.durationMs)
        require(value.byteSize.toLong() == 44 + value.durationMs * 32 && Regex("[a-f0-9]{64}").matches(value.checksum))
    }

    private fun manifest(value: CaptureManifestDto) {
        require(value.finalSequence in 0..CaptureWave.MAX_CHUNKS && value.durationMs in 0..CaptureWave.MAX_DURATION_MS)
        require(value.outcome in setOf("saved", "incomplete", "empty"))
        require(value.missingSequences.size <= value.finalSequence && value.missingSequences.distinct().size == value.missingSequences.size)
        require(value.missingSequences.all { it in 1..value.finalSequence })
        require(value.gaps.size <= CaptureWave.MAX_CHUNKS && value.gaps.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= CaptureWave.MAX_DURATION_MS })
        require(value.outcome != "saved" || (value.finalSequence > 0 && value.missingSequences.isEmpty()))
        require(value.outcome != "empty" || (value.finalSequence == 0 && value.durationMs == 0L))
    }

    private fun device(value: String) { require(value.isNotBlank() && value.length <= 128) }
    private fun uuid(value: String, random: Boolean = false) {
        val parsed = UUID.fromString(value)
        require(parsed.toString().equals(value, ignoreCase = true) && (!random || parsed.version() == 4))
    }
    private suspend fun <T> scoped(viewer: String, run: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = run()
        require(currentViewer() == viewer)
        Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
      catch (error: Exception) { Result.failure(error) }
}
