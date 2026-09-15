package com.we.meet.data

import com.we.meet.data.api.*
import com.we.meet.data.repository.RecordingUploadRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RecordingUploadRepositoryTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private var viewer: String? = "owner"
    private val config = RecordingUploadCapabilities(true, 8, listOf("wav"))
    private val queued get() = RecordingUploadState(id, "queued", 1)
    private var uploads = 0
    private var retryAttempt = 0
    private var afterUpload: () -> Unit = {}
    private val api = object : RecordingUploadApi {
        override suspend fun capabilities() = config
        override suspend fun state(recordId: String) = queued
        override suspend fun retry(recordId: String, body: RecordingUploadRetry): RecordingUploadState { retryAttempt = body.attempt; return queued }
        override suspend fun upload(key: RequestBody, audio: MultipartBody.Part, context: RequestBody, hotwords: RequestBody): RecordingUploadState {
            uploads++
            val bytes = Buffer(); audio.body.writeTo(bytes)
            assertEquals("audio", bytes.readUtf8())
            val requestKey = Buffer(); key.writeTo(requestKey); assertEquals(id, requestKey.readUtf8())
            afterUpload()
            return queued
        }
    }
    private val repository get() = RecordingUploadRepository(api) { viewer }

    @Test fun streamsSelectedFileAndPreservesIdempotencyKey() = runBlocking {
        assertEquals(queued, repository.upload("owner", id, "meeting.wav", 5, config, "Project", "Qwen") { "audio".byteInputStream() }.getOrThrow())
        assertEquals(1, uploads)
    }
    @Test fun invalidFileAndHotwordsDoNotSubmit() = runBlocking {
        assertTrue(repository.upload("owner", id, "meeting.wav", 9, config, "", "") { error("Must not open") }.isFailure)
        assertTrue(repository.upload("owner", id, "meeting.exe", 5, config, "", "") { error("Must not open") }.isFailure)
        assertTrue(repository.upload("owner", id, "meeting.wav", 5, config, "", "x".repeat(41)) { error("Must not open") }.isFailure)
        assertEquals(0, uploads)
    }
    @Test fun unknownSizeStillHasStreamingLimit() = runBlocking {
        assertTrue(repository.upload("owner", id, "meeting.wav", null, config, "", "") { "too much audio".byteInputStream() }.isFailure)
    }
    @Test fun accountChangeDiscardsUploadResultAndBlocksNewSubmission() = runBlocking {
        afterUpload = { viewer = "other" }
        assertTrue(repository.upload("owner", id, "meeting.wav", 5, config, "", "") { "audio".byteInputStream() }.isFailure)
        assertTrue(repository.upload("owner", id, "meeting.wav", 5, config, "", "") { error("Must not open") }.isFailure)
        assertEquals(1, uploads)
    }
    @Test fun retriesTheAttemptShownToTheUser() = runBlocking {
        repository.retry("owner", id, 3).getOrThrow()
        assertEquals(3, retryAttempt)
    }
}
