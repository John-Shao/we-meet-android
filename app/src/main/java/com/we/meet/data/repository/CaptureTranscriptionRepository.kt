package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranscriptionApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.capture.CaptureRetention
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Private owner ASR controls. The caller must persist paid intent before request(). */
class CaptureTranscriptionRepository(private val api: CaptureTranscriptionApi, private val currentViewer: () -> String?) {
    private val requestAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(CaptureAsrRequestDto::class.java).serializeNulls()

    suspend fun state(viewer: String, capture: String): Result<CaptureAsrStateDto> = scoped(viewer) {
        uuid(capture)
        api.state(capture).also { state ->
            state.audioRetention?.let(CaptureRetention::validate)
            state.activeJobId?.let(::uuid)
            require(state.results.size <= 10 && state.results.map { it.id }.distinct().size == state.results.size)
            var generation = Int.MAX_VALUE
            state.results.forEach { job(it); require(it.generation < generation); generation = it.generation }
        }
    }

    suspend fun request(viewer: String, capture: String, key: String, request: CaptureAsrRequestDto): Result<CaptureAsrCreatedDto> = scoped(viewer) {
        uuid(capture); uuid(key); validate(request)
        // expected_job_id:null is required by the server; default Moshi null omission is unsafe here.
        val body = requestAdapter.toJson(request).toRequestBody("application/json".toMediaType())
        api.request(capture, key, body).also { job(it.job); require(it.job.mode == if (request.live) "live" else "sealed") }
    }

    suspend fun cancel(viewer: String, capture: String, jobId: String): Result<CaptureAsrJobDto> = scoped(viewer) {
        uuid(capture); uuid(jobId)
        api.cancel(capture, jobId, "{}".toRequestBody("application/json".toMediaType())).also {
            job(it); require(it.id == jobId && it.status == "canceled")
        }
    }

    suspend fun preview(viewer: String, capture: String, jobId: String, after: Int): Result<CaptureAsrPreviewDto> = scoped(viewer) {
        uuid(capture); uuid(jobId); require(after in 0..MAX_FINALS)
        api.preview(capture, jobId, after).also { page ->
            require(page.jobId == jobId && page.status in STATUSES && page.lastSequence in 0..MAX_FINALS)
            require(page.results.size <= 50 && page.results.map { it.id }.distinct().size == page.results.size)
            var previous = after
            page.results.forEach {
                uuid(it.id)
                require(it.sequence > previous && it.sequence <= page.lastSequence)
                require(it.startMs in 0..CaptureWave.MAX_DURATION_MS &&
                    (it.endMs == null || it.endMs in it.startMs..CaptureWave.MAX_DURATION_MS))
                require(it.text.length in 1..10000 && it.language.length <= 16)
                previous = it.sequence
            }
            require(page.nextAfterSequence == null || (page.results.isNotEmpty() && page.nextAfterSequence == previous))
        }
    }

    private fun job(value: CaptureAsrJobDto) {
        uuid(value.id)
        require(value.generation > 0 && value.status in STATUSES)
        require(value.inputCount in 0..CaptureWave.MAX_CHUNKS && value.acknowledgedInputs in 0..value.inputCount && value.finalCount in 0..MAX_FINALS)
        require(value.mode in setOf("live", "sealed") && value.audioStatus in setOf("uploading", "saved", "incomplete", "empty"))
        require(value.errorCode.length <= 128 && (value.mode != "sealed" || value.inputClosed))
    }
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    private suspend fun <T> scoped(viewer: String, call: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = call()
        require(currentViewer() == viewer)
        Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }

    companion object {
        fun validate(request: CaptureAsrRequestDto) {
            request.expectedJobId?.let { require(UUID.fromString(it).toString() == it) }
        }
        const val MAX_FINALS = 20000
        private val STATUSES = setOf("queued", "running", "succeeded", "incomplete", "canceled")
    }
}
