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

@RunWith(AndroidJUnit4::class)
class CaptureRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "recovery-fixture-${UUID.randomUUID()}"
    private var currentViewer: String? = viewer
    private var journal = CaptureJournal.open(context, viewer, { currentViewer })
    private val api = FakeApi()
    private fun controller() = CaptureRecovery(viewer, journal, CaptureRepository(api) { currentViewer })
    @After fun close() { journal.close() }
    private fun reopen(): CaptureRecovery {
        journal.close()
        journal = CaptureJournal.open(context, viewer, { currentViewer })
        return controller()
    }

    @Test fun unknownCreateUsesSameKeyAndLeaseAfterRestart() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.failCreate = true
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertNull(journal.get(local.id).remote)
        val recovered = reopen()
        recovered.load()
        assertEquals(1, api.createKeys.size)
        val started = recovered.start(local.id)
        assertFalse(started.closed)
        assertEquals(listOf(local.createKey, local.createKey), api.createKeys)
        assertEquals(local.create.leaseKey, started.create.leaseKey)
    }

    @Test fun unknownStartIsReplayedWithoutOpeningLocalRecordingOnLoad() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        api.failCommand = "start"
        assertTrue(runCatching { first.start(local.id) }.isFailure)
        val pending = journal.get(local.id).command!!
        val recovered = reopen()
        assertTrue(recovered.load().single().closed)
        assertEquals(1, api.commandKeys.size)
        val started = recovered.start(local.id)
        assertTrue(started.interrupted)
        assertEquals(pending.key, api.commandKeys[1])
        assertEquals(listOf("start", "start", "interrupt", "resume"), api.commands)
        assertFalse(started.closed)
    }

    @Test fun uploadUnknownOutcomeReconcilesReceiptWithoutResendingAudio() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        first.start(local.id)
        journal.append(local.id, ShortArray(16000))
        api.failUpload = true
        assertTrue(runCatching { first.uploadPending(local.id) }.isFailure)
        assertTrue(journal.get(local.id).pendingBytes > 0)
        val recovered = reopen()
        recovered.load()
        val synced = recovered.retryUploads(local.id)
        assertTrue(synced.closed && synced.interrupted)
        assertEquals(0, synced.pendingBytes)
        assertEquals(1, api.uploadCalls)
    }

    @Test fun unknownSealRetainsFrozenIntentAndCompletesAfterRestart() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        first.start(local.id)
        journal.append(local.id, ShortArray(16000))
        first.closeLocally(local.id, false)
        api.failSeal = true
        assertTrue(runCatching { first.finish(local.id) }.isFailure)
        val intent = journal.get(local.id).sealIntent
        assertNotNull(intent)
        val recovered = reopen()
        recovered.load()
        val finished = recovered.finish(local.id)
        assertTrue(finished.sealed)
        assertEquals(listOf(intent, intent), api.seals)
        assertEquals(1, api.uploadCalls)
        assertTrue(journal.chunks(local.id).isEmpty())
    }

    @Test fun rejectedCasFailsCurrentActionAndOnlyNextActionGetsNewIntent() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.rejectCommand = true
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertEquals(1, api.commandKeys.size)
        assertNull(journal.get(local.id).command)
        assertTrue(journal.get(local.id).closed)
        controller.start(local.id)
        assertEquals(2, api.commandKeys.size)
        assertNotEquals(api.commandKeys[0], api.commandKeys[1])
    }

    @Test fun failedPauseKeepsHardwareClosedAndExactCommandForRetry() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        controller.start(local.id)
        api.failCommand = "pause"
        assertTrue(runCatching { controller.pause(local.id) }.isFailure)
        assertTrue(journal.get(local.id).closed)
        val key = journal.get(local.id).command!!.key
        controller.retryUploads(local.id)
        assertNull(journal.get(local.id).command)
        assertEquals(key, api.commandKeys.last())
        assertEquals("paused", journal.get(local.id).remote!!.status)
    }

    @Test fun mismatchedReceiptNeverDeletesAudioOrSeals() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        controller.start(local.id)
        journal.append(local.id, ShortArray(16))
        api.corruptReceipt = true
        assertTrue(runCatching { controller.finish(local.id) }.isFailure)
        assertEquals(76, journal.get(local.id).pendingBytes)
        assertNull(journal.get(local.id).sealIntent)
        assertTrue(api.seals.isEmpty())
    }

    @Test fun accountSwitchDuringRequestLeavesRecoveryPrivateAndUnconfirmed() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.afterCreate = { currentViewer = "other" }
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertTrue(runCatching { controller.load() }.isFailure)
        currentViewer = viewer
        assertNull(journal.get(local.id).remote)
        assertEquals(local.createKey, journal.get(local.id).createKey)
    }

    @Test fun localInterruptionWhileStartIsPendingCannotReopenRecording() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.afterCreate = { controller.closeLocally(local.id, true); Unit }
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertTrue(journal.get(local.id).closed)
        assertTrue(journal.get(local.id).interrupted)
    }

    /** In-memory protocol fixture only: no server, credentials, microphone or provider. */
    private class FakeApi : CaptureApi {
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
}
