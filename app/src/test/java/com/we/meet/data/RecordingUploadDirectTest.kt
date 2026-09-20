package com.we.meet.data

import com.we.meet.data.api.*
import com.we.meet.data.repository.RecordingUploadRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Importing by presigned direct upload.
 *
 * This is the only path that can carry a file past the multipart ceiling, and it
 * is the only request the app makes to a third-party host. Both facts are worth
 * pinning: the declaration must match the bytes, and a retry after the transfer
 * must not re-upload them.
 */
class RecordingUploadDirectTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val storageName = "record-uploads/abc.wav"
    private val contentType = "audio/wav"
    private var viewer: String? = "owner"
    private val config = RecordingUploadCapabilities(
        available = true, maxBytes = 8, extensions = listOf("wav"),
        directUploadAvailable = true, directMaxBytes = 4096,
    )
    private val queued get() = RecordingUploadState(id, "queued", 1)

    private var presigns = 0
    private var completes = 0
    private var multipart = 0
    private var puts = 0
    private var putSucceeds = true
    private var lastComplete: RecordingUploadComplete? = null
    private var lastPresign: RecordingUploadPresign? = null

    private val api = object : RecordingUploadApi {
        override suspend fun capabilities() = config
        override suspend fun state(recordId: String) = queued
        override suspend fun retry(recordId: String, body: RecordingUploadRetry) = queued
        override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody): RecordingUploadState {
            multipart++
            return queued
        }
        override suspend fun multipartBegin(body: RecordingUploadBegin) = error("Chunked upload not configured")
        override suspend fun multipartResume(sessionId: String) = error("Chunked upload not configured")
        override suspend fun multipartSign(sessionId: String, body: RecordingUploadSign) = error("Chunked upload not configured")
        override suspend fun multipartComplete(sessionId: String, body: RecordingUploadFinish) = error("Chunked upload not configured")
        override suspend fun multipartAbort(sessionId: String) = error("Chunked upload not configured")
        override suspend fun presign(body: RecordingUploadPresign): RecordingUploadTicket {
            presigns++
            lastPresign = body
            return RecordingUploadTicket("https://bucket.example/put?sig=abc", storageName, mapOf("Content-Type" to body.contentType))
        }
        override suspend fun complete(body: RecordingUploadComplete): RecordingUploadState {
            completes++
            lastComplete = body
            return queued
        }
    }

    private val storage = RecordingStorage { _, _, size, open ->
        puts++
        // Consume the stream the way a real PUT would, so a body that cannot be
        // read is a failure here rather than a false success.
        open().use { it.readBytes() }
        // Length agreement is enforced by the signed ContentLength and by object
        // storage; the repository's contract is only "did the bytes land?".
        size > 0 && putSucceeds
    }

    private val repository get() = RecordingUploadRepository(api, { viewer }, storage)

    @Test fun signsUploadsThenAdoptsWithTheSameDeclaration() = runBlocking {
        val result = repository.uploadDirect(
            "owner", id, "Long.wav", 4096, config, "Project", "Qwen",
            { "audio".byteInputStream() }, null, contentType,
        )
        assertEquals(queued, result.getOrThrow())
        assertEquals(1, puts)
        assertEquals(1, presigns)
        assertEquals(1, completes)
        // The row is adopted against the key the server signed, not a fresh one.
        assertEquals(storageName, lastComplete!!.storageName)
        assertEquals(4096L, lastComplete!!.size)
        assertEquals(contentType, lastComplete!!.contentType)
        assertEquals("Project", lastComplete!!.context)
        // Multipart is never used for a file that only the direct path can carry.
        assertEquals(0, multipart)
    }

    @Test fun reusesTheTicketSoASecondAttemptDoesNotSignANewKey() = runBlocking {
        // The bytes already landed; asking for a new key would orphan the first
        // object and upload the file twice.
        val ticket = RecordingUploadTicket("https://bucket.example/put?sig=abc", storageName, mapOf("Content-Type" to contentType))
        val result = repository.uploadDirect(
            "owner", id, "Long.wav", 4096, config, "", "",
            { "audio".byteInputStream() }, ticket, contentType,
        )
        assertTrue(result.isSuccess)
        assertEquals(0, presigns)
        assertEquals(1, puts)
        assertEquals(1, completes)
    }

    @Test fun aRefusedStoragePutIsAFailureNotASilentAdoption() = runBlocking {
        // Adopting an object that was never stored would leave the job pointing
        // at nothing.
        putSucceeds = false
        val result = repository.uploadDirect(
            "owner", id, "Long.wav", 4096, config, "", "",
            { "audio".byteInputStream() }, null, contentType,
        )
        assertTrue(result.isFailure)
        assertEquals(0, completes)
    }

    @Test fun refusesAFileOverTheDirectCeilingWithoutSigning() = runBlocking {
        val result = repository.uploadDirect(
            "owner", id, "Long.wav", 4097, config, "", "",
            { error("Must not open") }, null, contentType,
        )
        assertTrue(result.isFailure)
        assertEquals(0, presigns)
    }

    @Test fun refusesADeclaredTypeThatDoesNotMatchTheSignature() = runBlocking {
        // The server signs a content type and the client must send that one; a
        // mismatch means the request cannot be replayed against the signature.
        val mismatched = object : RecordingUploadApi by api {
            override suspend fun presign(body: RecordingUploadPresign): RecordingUploadTicket {
                presigns++
                return RecordingUploadTicket("https://bucket.example/put?sig=abc", storageName, mapOf("Content-Type" to "video/mp4"))
            }
        }
        val result = RecordingUploadRepository(mismatched, { viewer }, storage).uploadDirect(
            "owner", id, "Long.wav", 4096, config, "", "",
            { "audio".byteInputStream() }, null, contentType,
        )
        assertTrue(result.isFailure)
        assertEquals(0, puts)
    }

    @Test fun accountChangeDiscardsAResultAndBlocksTheNextUpload() = runBlocking {
        val result = RecordingUploadRepository(api, { viewer }, storage).uploadDirect(
            "other", id, "Long.wav", 4096, config, "", "",
            { "audio".byteInputStream() }, null, contentType,
        )
        assertTrue(result.isFailure)
        assertEquals(0, presigns)
    }

    @Test fun reportsWhetherALargeFileNeedsTheDirectPath() {
        // Only when the path is actually reachable: a server with direct uploads
        // off must keep rejecting an oversized file locally.
        assertTrue(repository.needsDirectUpload(4096, config))
        assertFalse(repository.needsDirectUpload(8, config))
        assertFalse(repository.needsDirectUpload(null, config))
        assertFalse(repository.needsDirectUpload(4096, config.copy(directUploadAvailable = false)))
        // No storage client means no direct path, whatever the server offers.
        val multipartOnly = RecordingUploadRepository(api, { viewer })
        assertFalse(multipartOnly.needsDirectUpload(4096, config))
        assertEquals(8L, multipartOnly.maxBytes(config))
        assertEquals(4096L, repository.maxBytes(config))
    }

    @Test fun retriesOnlyCompletionAfterAResponseIsLost() = runBlocking {
        var held: RecordingUploadTicket? = null
        var calls = 0
        val delayed = object : RecordingUploadApi by api {
            override suspend fun complete(body: RecordingUploadComplete): RecordingUploadState {
                calls++
                if (calls == 1) throw java.io.IOException("Response lost")
                return queued
            }
        }
        val repository = RecordingUploadRepository(delayed, { viewer }, storage)
        val first = repository.uploadDirect("owner", id, "Long.wav", 4096, config, "", "",
            { "audio".byteInputStream() }, null, contentType, onTicket = { held = it })
        assertTrue(first.isFailure)
        assertTrue(held!!.uploaded)
        val progress = mutableListOf<Long>()
        val retry = repository.uploadDirect("owner", id, "Long.wav", 4096, config, "", "",
            { error("Already stored") }, held, contentType, onProgress = { sent, _ -> progress.add(sent) })
        assertEquals(queued, retry.getOrThrow())
        assertEquals(1, puts)
        assertEquals(1, presigns)
        assertEquals(listOf(4096L), progress)
    }
}
