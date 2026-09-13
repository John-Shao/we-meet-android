package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.CaptureApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureJournal
import com.we.meet.data.capture.CaptureRecovery
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.repository.CaptureRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

/** In-memory protocol fixture only: no server, credentials, microphone or provider. */
internal class CaptureProtocolFixture : CaptureApi {
    override suspend fun audioCapabilities() = CaptureAudioCapabilitiesDto(false, "rollout_disabled")
    var failCreate = false
    var failCommand: String? = null
    var rejectCommand = false
    var failUpload = false
    var failSeal = false
    var corruptReceipt = false
    var afterCreate: (suspend () -> Unit)? = null
    val createKeys = mutableListOf<String>()
    val commandKeys = mutableListOf<String>()
    val commands = mutableListOf<String>()
    val seals = mutableListOf<SealCaptureDto>()
    var uploadCalls = 0
    lateinit var state: CaptureDto
    private val operations = mutableMapOf<String, CaptureOperationDto>()
    private val receipts = mutableMapOf<Int, CaptureAudioReceiptDto>()
    private fun operation(key: String): CaptureOperationDto {
        val previous = operations[key]
        if (previous != null) return previous.copy(replayed = true, capture = state)
        return CaptureOperationDto(UUID.randomUUID().toString(), false, state, state).also { operations[key] = it }
    }
    override suspend fun create(key: String, request: CreateCaptureDto): CaptureOperationDto {
        createKeys += key
        if (!::state.isInitialized) state = CaptureDto(UUID.randomUUID().toString(), UUID.randomUUID().toString(), request.deviceId,
            "preparing", 1, "2026-09-13T00:00:00Z", mediaStatus = "not_connected", lastAckedSequence = 0)
        val response = operation(key)
        afterCreate?.invoke()
        if (failCreate) { failCreate = false; throw IOException("Synthetic lost response") }
        return response
    }
    override suspend fun read(captureId: String) = state
    override suspend fun command(captureId: String, key: String, lease: String, request: CaptureCommandDto): CaptureOperationDto {
        commandKeys += key; commands += request.command
        if (rejectCommand) {
            rejectCommand = false
            throw HttpException(Response.error<Any>(409, "{}".toResponseBody("application/json".toMediaType())))
        }
        if (!operations.containsKey(key)) {
            check(state.revision == request.expectedRevision)
            val status = when (request.command) { "start", "resume" -> "recording"; "pause" -> "paused"; "interrupt" -> "interrupted"; "stop" -> "stopping"; "finalize" -> "stopped"; else -> error("Unknown command") }
            state = state.copy(status = status, revision = state.revision + 1)
        }
        val response = operation(key)
        if (failCommand == request.command) { failCommand = null; throw IOException("Synthetic lost response") }
        return response
    }
    override suspend fun receipts(captureId: String, after: Int) = CaptureReceiptsDto(receipts.values.filter { it.sequence > after }.sortedBy { it.sequence })
    override suspend fun upload(captureId: String, lease: String, fields: Map<String, RequestBody>, audio: MultipartBody.Part): CaptureAudioReceiptDto {
        uploadCalls++
        fun field(name: String) = Buffer().also { fields.getValue(name).writeTo(it) }.readUtf8()
        val info = CaptureWave.inspect(Buffer().also { audio.body.writeTo(it) }.readByteArray())
        val receipt = CaptureAudioReceiptDto(UUID.randomUUID().toString(), field("sequence").toInt(), field("start_ms").toLong(), info.durationMs,
            if (corruptReceipt) "a".repeat(64) else info.checksum, info.byteSize, true)
        receipts[receipt.sequence] = receipt
        if (failUpload) { failUpload = false; throw IOException("Synthetic lost response") }
        return receipt
    }
    override suspend fun seal(captureId: String, lease: String, request: SealCaptureDto): CaptureManifestDto {
        seals += request
        if (failSeal) { failSeal = false; throw IOException("Synthetic lost response") }
        return CaptureManifestDto(request.finalSequence, if (request.clientInterrupted) "incomplete" else if (request.finalSequence == 0) "empty" else "saved",
            receipts.values.sumOf { it.durationMs }, emptyList(), emptyList())
    }
}
