package com.we.meet.data

import com.we.meet.data.api.*
import com.we.meet.data.repository.ChunkedUploadRequest
import com.we.meet.data.repository.RecordingUploadCancelled
import com.we.meet.data.repository.RecordingUploadRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Resumable chunked uploads, pinned at the repository boundary.
 *
 * The point of the path is that a break does not mean starting over, so what
 * matters is what is *not* re-sent and what is refused: a resumed upload must
 * skip parts storage already holds, and a part that did not store must not be
 * reported as complete.
 */
class RecordingUploadChunkedTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val session = "22222222-2222-4222-8222-222222222222"
    private val partSize = 5L * 1024 * 1024
    private val size = partSize * 3
    private var viewer: String? = "owner"
    private val config = RecordingUploadCapabilities(
        available = true, maxBytes = 1024, extensions = listOf("wav"),
        directUploadAvailable = true, directMaxBytes = 6L * 1024 * 1024 * 1024,
    )
    private val queued get() = RecordingUploadState(id, "queued", 1)

    private val held = linkedMapOf<Int, RecordingUploadHeldPart>()
    private var began = 0
    private var beginDiarization = false
    private var signedBatches = mutableListOf<List<Int>>()
    private var completed: List<RecordingUploadPartTag>? = null
    private var aborted = 0
    /** Which parts storage refuses, to model a transfer that broke. */
    private var refuseParts: Set<Int> = emptySet()
    private var resumeShouldFail = false
    private var assembled = false
    private var completedJob: RecordingUploadState? = null

    private val api = object : RecordingUploadApi {
        override suspend fun capabilities() = config
        override suspend fun state(recordId: String) = queued
        override suspend fun retry(recordId: String, body: RecordingUploadRetry) = queued
        override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody, diarization: RequestBody) = queued
        override suspend fun presign(body: RecordingUploadPresign): RecordingUploadTicket = error("Not used")
        override suspend fun complete(body: RecordingUploadComplete) = queued

        override suspend fun multipartBegin(body: RecordingUploadBegin): RecordingUploadPlan {
            began++
            beginDiarization = body.diarization
            // A resumed upload always starts from what storage holds, never from
            // what the client believes it sent.
            return plan()
        }

        override suspend fun multipartResume(sessionId: String): RecordingUploadPlan {
            if (resumeShouldFail) error("session gone")
            return plan()
        }

        override suspend fun multipartSign(sessionId: String, body: RecordingUploadSign): RecordingUploadPlan {
            signedBatches.add(body.parts)
            return plan().copy(
                parts = body.parts.map {
                    RecordingUploadPartPlan(it, "https://bucket.example/part/$it", partSize)
                },
            )
        }

        override suspend fun multipartComplete(sessionId: String, body: RecordingUploadFinish): RecordingUploadState {
            completed = body.parts
            return queued
        }

        override suspend fun multipartAbort(sessionId: String) {
            aborted++
        }
    }

    private fun plan() = RecordingUploadPlan(
        sessionId = session,
        size = size,
        partSize = partSize,
        partCount = 3,
        uploaded = held.values.toList(),
        uploadedBytes = held.values.sumOf { it.size },
        completionPending = assembled,
        job = completedJob,
    )

    private var partPuts = mutableListOf<Long>()
    private val partStorage = RecordingPartStorage { url, length, open, onProgress, cancel ->
        val number = url.substringAfterLast('/').toInt()
        if (number in refuseParts) return@RecordingPartStorage null
        // Consume the range so a stream that cannot be opened surfaces here.
        var read = 0L
        open().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                if (cancel()) return@RecordingPartStorage null
                val count = stream.read(buffer)
                if (count < 0) break
                read += count
            }
        }
        onProgress(read)
        partPuts.add(length)
        "etag-$number"
    }

    private val repository get() =
        RecordingUploadRepository(api, currentViewer = { viewer }, storage = RecordingStorage { _, _, _, _ -> true }, partStorage = partStorage)

    private fun request(
        resumeFrom: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ) = ChunkedUploadRequest(
        viewer = "owner", key = id, name = "Long.wav", size = size, config = config,
        context = "Project", hotwords = "Qwen", contentType = "audio/wav",
        resumeFrom = resumeFrom,
        // Every offset opens a fresh stream, the way a provider must be re-opened.
        openAt = { _offset, length -> ByteArrayInputStream(ByteArray(length.toInt())) as InputStream },
        onProgress = onProgress,
        cancelled = cancelled,
    )

    @Test fun carriesSpeakerOptionInTheChunkedDeclaration() = runBlocking {
        repository.uploadChunked(request().copy(diarization = true)).getOrThrow()
        assertTrue(beginDiarization)
    }

    @Test fun resumesAnAssembledObjectWithoutOpeningTheFileOrSigningParts() = runBlocking {
        assembled = true
        val result = repository.uploadChunked(request(resumeFrom = session).copy(openAt = { _, _ -> error("No file read expected") }))
        assertTrue(result.isSuccess)
        assertTrue(partPuts.isEmpty())
        assertTrue(signedBatches.isEmpty())
        assertEquals(emptyList<RecordingUploadPartTag>(), completed)
    }

    @Test fun decodesTheCompletedIntentResponseWithoutRequiringAPlan() {
        val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        val result = moshi.adapter(RecordingUploadPlan::class.java).fromJson("""{"job":{"record_id":"$id","status":"queued","attempt":1}}""")!!
        assertEquals(queued, result.job)
    }

    @Test fun acceptsACompletedIntentWithoutTransferringAgain() = runBlocking {
        completedJob = queued
        assertEquals(queued, repository.uploadChunked(request()).getOrThrow())
        assertTrue(partPuts.isEmpty())
        assertNull(completed)
    }

    @Test fun uploadsEveryPartAndAdoptsInOrder() = runBlocking {
        val result = repository.uploadChunked(request())
        assertTrue(result.isSuccess)
        assertEquals(1, began)
        assertEquals(listOf(partSize, partSize, partSize), partPuts)
        // Part order is what makes the object correct.
        assertEquals(listOf(1, 2, 3), completed!!.map { it.partNumber })
        assertEquals(listOf("etag-1", "etag-2", "etag-3"), completed!!.map { it.etag })
    }

    @Test fun skipsThePartsStorageAlreadyHolds() = runBlocking {
        // The entire reason this path exists: a break costs the part in flight,
        // not the whole transfer.
        held[1] = RecordingUploadHeldPart(1, "etag-1", partSize)
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = repository.uploadChunked(request(onProgress = { s, t -> progress.add(s to t) }))
        assertTrue(result.isSuccess)
        // Only the two missing parts were signed and sent.
        assertEquals(listOf(listOf(2, 3)), signedBatches)
        assertEquals(listOf(partSize, partSize), partPuts)
        // Progress starts from what already landed, not from zero.
        assertEquals(partSize to size, progress.first())
        assertEquals(size to size, progress.last())
    }

    @Test fun aPartThatDidNotStoreIsNotReportedAsDone() = runBlocking {
        refuseParts = setOf(2)
        val result = repository.uploadChunked(request())
        assertTrue(result.isFailure)
        assertNull(completed)
    }

    @Test fun resumesTheRememberedSessionWithoutReOpeningTheIntent() = runBlocking {
        val result = repository.uploadChunked(request(resumeFrom = session))
        assertTrue(result.isSuccess)
        // The remembered session was used: no second begin.
        assertEquals(0, began)
    }

    @Test fun fallsBackToBeginWhenTheRememberedSessionIsGone() = runBlocking {
        // A stale id must not strand the upload.
        resumeShouldFail = true
        val result = repository.uploadChunked(request(resumeFrom = session))
        assertTrue(result.isSuccess)
        assertEquals(1, began)
    }

    @Test fun aCancelStopsRatherThanCompleting() = runBlocking {
        var calls = 0
        val result = repository.uploadChunked(
            request(cancelled = { calls++ > 0 })
        )
        // A cancel is a failure the caller can act on, not a thrown exception:
        // it must be distinguishable from the coroutine machinery's own signal.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RecordingUploadCancelled)
        assertNull(completed)
    }

    @Test fun refusesAFileOverTheDirectCeilingWithoutOpeningASession() = runBlocking {
        val result = repository.uploadChunked(
            request().copy(size = config.directMaxBytes + 1)
        )
        assertTrue(result.isFailure)
        assertEquals(0, began)
    }

    @Test fun abortTellsTheServerBecauseIncompletePartsAreBilled() = runBlocking {
        assertTrue(repository.abortChunked("owner", session).isSuccess)
        assertEquals(1, aborted)
    }

    @Test fun refusesAMalformedSessionIdWhenAborting() = runBlocking {
        assertTrue(repository.abortChunked("owner", "not-a-uuid").isFailure)
        assertEquals(0, aborted)
    }

    @Test fun withoutAPartStorageTheChunkedPathIsUnavailable() = runBlocking {
        val multipartOnly = RecordingUploadRepository(api, currentViewer = { viewer })
        assertTrue(multipartOnly.uploadChunked(request()).isFailure)
        assertEquals(0, began)
    }

    @Test fun anAccountChangeDiscardsTheResult() = runBlocking {
        val result = RecordingUploadRepository(
            api, currentViewer = { viewer }, storage = null, partStorage = partStorage,
        ).uploadChunked(request().copy(viewer = "other"))
        assertTrue(result.isFailure)
    }
}
