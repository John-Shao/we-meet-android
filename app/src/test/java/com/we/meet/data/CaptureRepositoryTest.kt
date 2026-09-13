package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureApi
import com.we.meet.data.api.dto.CaptureCommandDto
import com.we.meet.data.api.dto.CreateCaptureDto
import com.we.meet.data.api.dto.SealCaptureDto
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.repository.CaptureRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureRepositoryTest {
    private val capture = "11111111-1111-4111-8111-111111111111"
    private val record = "22222222-2222-4222-8222-222222222222"
    private val key = "33333333-3333-4333-8333-333333333333"
    private val lease = "44444444-4444-4444-8444-444444444444"
    private var viewer: String? = "owner"
    private val requests = mutableListOf<Request>()
    private val create = CreateCaptureDto("device", lease, "Fixture recording")
    private fun state(revision: Int = 1, status: String = "preparing") = """
        {"id":"$capture","record_id":"$record","device_id":"device","status":"$status",
        "revision":$revision,"started_at":"2026-09-13T00:00:00Z","media_status":"not_connected","last_acked_sequence":0}
    """
    private fun operation() = """{"operation_id":"$key","replayed":true,"result":${state()},"capture":${state(3, "paused")}}"""
    private fun retention() = """{"mode":"text","temporary_until":"2030-01-01T10:00:00Z","retry_until":"2030-01-01T09:30:00Z","expired":false,"cleanup_status":"not_started","cleanup_error":"","deleted_at":null}"""
    private fun textState() = state().trim().removeSuffix("}") + ",\"audio_retention\":" + retention() + "}"
    private fun receipt(audio: ByteArray, sequence: Int = 1, stored: Boolean = true): String {
        val info = CaptureWave.inspect(audio)
        return """{"id":"$record","sequence":$sequence,"start_ms":0,"duration_ms":${info.durationMs},
            "checksum":"${info.checksum}","byte_size":${info.byteSize},"stored":$stored}"""
    }
    private fun repository(reply: (Request) -> Pair<Int, String>): CaptureRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            assertEquals("meeting.invalid", chain.request().url.host)
            assertEquals("no-store", chain.request().header("Cache-Control"))
            assertFalse(chain.request().url.toString().contains(lease))
            val (code, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder().baseUrl("https://meeting.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(CaptureApi::class.java)
        return CaptureRepository(api) { viewer }
    }

    @Test fun createReplayKeepsCurrentStateAndImmutableKeyBody() = runBlocking {
        val bodies = mutableListOf<String>()
        val repo = repository { request ->
            assertEquals(key, request.header("Idempotency-Key"))
            bodies += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            200 to operation()
        }
        repeat(2) {
            val result = repo.create("owner", key, create).getOrThrow()
            assertEquals(1, result.result.revision)
            assertEquals(3, result.capture.revision)
            assertEquals("paused", result.capture.status)
        }
        assertEquals(bodies[0], bodies[1])
        assertFalse(create.toString().contains(lease))
    }

    @Test fun textCaptureRequiresValidRetentionWithoutReplacingItsOriginalIntent() = runBlocking {
        var response = operation()
        val repo = repository { 200 to response }
        val intent = create.copy(retentionMode = "text")
        assertTrue(repo.create("owner", key, intent).isFailure)
        response = """{"operation_id":"$key","replayed":true,"result":${textState()},"capture":${textState()}}"""
        assertEquals("text", repo.create("owner", key, intent).getOrThrow().capture.audioRetention!!.mode)
        assertEquals(2, requests.size)
        val bodies = requests.map { Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8() }
        assertEquals(bodies[0], bodies[1])
        assertTrue(requests.all { it.header("Idempotency-Key") == key })
    }

    @Test fun storagePreflightRequiresExplicitConsistentAvailability() = runBlocking {
        var reply = """{"text_audio_available":true,"text_audio_error":""}"""
        val repo = repository { 200 to reply }
        assertTrue(repo.textAudioAvailable("owner").getOrThrow())
        assertEquals("/api/v1.0/capture-audio-capabilities/", requests.single().url.encodedPath)
        reply = """{"text_audio_available":false,"text_audio_error":"versioned_storage_requires_purge"}"""
        assertFalse(repo.textAudioAvailable("owner").getOrThrow())
        reply = """{"text_audio_available":true,"text_audio_error":"storage_unavailable"}"""
        assertTrue(repo.textAudioAvailable("owner").isFailure)
        reply = "{}"
        assertTrue(repo.textAudioAvailable("owner").isFailure)
    }

    @Test fun lateStorageAdmissionCannotCrossAccounts() = runBlocking {
        val repo = repository {
            viewer = "other"
            200 to """{"text_audio_available":true,"text_audio_error":""}"""
        }
        assertTrue(repo.textAudioAvailable("owner").isFailure)
        assertTrue(repo.textAudioAvailable("owner").isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun expiredDoesNotImplyDeletedAndMalformedDeletionStateIsRejected() = runBlocking {
        var response = textState().replace("\"expired\":false", "\"expired\":true")
        val repo = repository { 200 to response }
        val expired = repo.read("owner", capture).getOrThrow().audioRetention!!
        assertTrue(expired.expired)
        assertNull(expired.deletedAt)
        response = response.replace("not_started", "complete")
        assertTrue(repo.read("owner", capture).isFailure)
        response = response.replace("\"deleted_at\":null", "\"deleted_at\":\"2030-01-01T11:00:00Z\"")
        assertEquals("complete", repo.read("owner", capture).getOrThrow().audioRetention!!.cleanupStatus)
    }

    @Test fun unknownCommandOutcomeIsNotAutomaticallyRetriedOrRekeyed() = runBlocking {
        val repo = repository { 503 to "{}" }
        assertTrue(repo.command("owner", capture, key, lease, CaptureCommandDto("stop", "device", 2)).isFailure)
        assertEquals(1, requests.size)
        assertEquals(key, requests.single().header("Idempotency-Key"))
        assertEquals(lease, requests.single().header("X-Capture-Lease"))
    }

    @Test fun validWaveAndExactUploadReceipt() = runBlocking {
        val audio = CaptureWave.encode(ShortArray(16000) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE })
        val info = CaptureWave.inspect(audio)
        assertEquals(1000L, info.durationMs)
        assertEquals(32044, audio.size)
        val buffer = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, buffer.getInt(24))
        assertEquals(Short.MIN_VALUE, buffer.getShort(44))
        val repo = repository { request ->
            val multipart = request.body as MultipartBody
            val part = multipart.parts.last()
            assertArrayEquals(audio, Buffer().also { part.body.writeTo(it) }.readByteArray())
            assertEquals(lease, request.header("X-Capture-Lease"))
            200 to receipt(audio)
        }
        assertTrue(repo.upload("owner", capture, lease, "device", 1, 0, info.checksum, audio).getOrThrow().stored)
    }

    @Test fun wrongOrUnstoredUploadReceiptNeverAcknowledgesLocalAudio() = runBlocking {
        val audio = CaptureWave.encode(ShortArray(16))
        var stored = false
        val repo = repository { 200 to receipt(audio, if (stored) 2 else 1, stored) }
        assertTrue(repo.upload("owner", capture, lease, "device", 1, 0, CaptureWave.inspect(audio).checksum, audio).isFailure)
        stored = true
        assertTrue(repo.upload("owner", capture, lease, "device", 1, 0, CaptureWave.inspect(audio).checksum, audio).isFailure)
    }

    @Test fun receiptPaginationRejectsDuplicatesAndNonadvancingCursor() = runBlocking {
        val audio = CaptureWave.encode(ShortArray(16))
        val repo = repository { 200 to """{"results":[${receipt(audio)}],"next_after_sequence":0}""" }
        assertTrue(repo.receipts("owner", capture).isFailure)
        val duplicate = repository { 200 to """{"results":[${receipt(audio)},${receipt(audio)}]}""" }
        assertTrue(duplicate.receipts("owner", capture).isFailure)
    }

    @Test fun invalidInputFailsBeforeSendingAudioOrCreatingCapture() = runBlocking {
        val repo = repository { error("No request allowed") }
        assertTrue(repo.create("owner", key, create.copy(retentionMode = "unknown")).isFailure)
        assertTrue(repo.create("owner", key, create.copy(leaseKey = "1-1-1-1-1")).isFailure)
        val audio = CaptureWave.encode(ShortArray(16))
        assertTrue(repo.upload("owner", capture, lease, "device", 1, 0, "wrong-checksum", audio).isFailure)
        audio[24] = 1
        assertTrue(repo.upload("owner", capture, lease, "device", 1, 0, "wrong-checksum", audio).isFailure)
        assertEquals(0, requests.size)
        assertTrue(runCatching { CaptureWave.encode(ShortArray(15)) }.isFailure)
        assertTrue(runCatching { CaptureWave.encode(ShortArray(160016)) }.isFailure)
    }

    @Test fun interruptedSealCannotClaimSavedAndMustMatchFinalSequence() = runBlocking {
        val repo = repository { 200 to """{"final_sequence":1,"outcome":"saved","duration_ms":1000,"missing_sequences":[],"gaps":[]}""" }
        assertTrue(repo.seal("owner", capture, lease, SealCaptureDto("device", 1, true)).isFailure)
        assertTrue(repo.seal("owner", capture, lease, SealCaptureDto("device", 2, false)).isFailure)
        assertEquals("saved", repo.seal("owner", capture, lease, SealCaptureDto("device", 1, false)).getOrThrow().outcome)
    }

    @Test fun lateResponseFromPriorAccountCannotBeAdopted() = runBlocking {
        val repo = repository { viewer = "other"; 200 to state() }
        assertTrue(repo.read("owner", capture).isFailure)
        assertTrue(repo.read("owner", capture).isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun coroutineCancellationIsNeverTurnedIntoRecoverableFailure() = runBlocking {
        val api = java.lang.reflect.Proxy.newProxyInstance(CaptureApi::class.java.classLoader, arrayOf(CaptureApi::class.java)) { _, _, _ ->
            throw CancellationException("fixture canceled")
        } as CaptureApi
        try {
            CaptureRepository(api) { viewer }.read("owner", capture)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
