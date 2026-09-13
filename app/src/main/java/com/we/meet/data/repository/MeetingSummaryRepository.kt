package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingSummaryApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Exact record, current account and explicit paid intents; never retries internally. */
class MeetingSummaryRepository(private val api: MeetingSummaryApi, private val currentViewer: () -> String?) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val summaryAdapter = moshi.adapter(SummaryRequestDto::class.java).serializeNulls()
    private val automationAdapter = moshi.adapter(SummaryAutomationRequestDto::class.java)

    suspend fun progress(viewer: String, record: String): Result<SummaryProgressDto> = scoped(viewer, record) {
        api.progress(record).also {
            require(it.revision > 0)
            it.job?.let(::job)
            require(it.readyStages.distinct().size == it.readyStages.size && it.readyStages.all { stage -> stage in STAGES })
            it.nextUpdateAt?.let(OffsetDateTime::parse)
            require(it.blockedReason == null || it.blockedReason.length <= 128)
        }
    }
    suspend fun automation(viewer: String, record: String): Result<SummaryAutomationDto> = scoped(viewer, record) {
        api.automation(record).also(::automationState)
    }
    suspend fun request(viewer: String, record: String, key: String, request: SummaryRequestDto): Result<SummaryAcceptedDto> = scoped(viewer, record) {
        uuid(key); validate(request)
        api.request(record, key, summaryAdapter.toJson(request).toRequestBody(JSON)).also {
            uuid(it.requestId); job(it.job)
            require(it.dispatchState in setOf("pending", "sent", "abandoned") && it.job.stage == request.stage)
        }
    }
    suspend fun control(viewer: String, record: String, key: String, request: SummaryAutomationRequestDto): Result<SummaryAutomationAcceptedDto> = scoped(viewer, record) {
        uuid(key); validate(request)
        api.control(record, key, automationAdapter.toJson(request).toRequestBody(JSON)).also {
            uuid(it.commandId); automationState(it.result); automationState(it.current)
            require(it.result.enabled == request.enabled && it.result.revision.toLong() == request.expectedRevision.toLong() + 1)
            require(it.current.revision >= it.result.revision)
        }
    }
    private fun job(value: SummaryJobDto) {
        uuid(value.id)
        require(value.attempt > 0 && value.generation > 0 && value.inputRevision > 0)
        require(value.stage in STAGES && value.status in setOf("queued", "running", "succeeded", "partial", "failed", "canceled"))
        require(value.errorCode.length <= 128)
        OffsetDateTime.parse(value.updatedAt)
        value.chunkProgress?.let { require(it.total in 0..32 && it.completed in 0..it.total) }
    }
    private fun automationState(value: SummaryAutomationDto) {
        require(value.revision >= 0 && value.errorCode.length <= 128)
        require(value.state in setOf("off", "waiting", "generating", "completed", "needs_attention"))
        require(value.revision != 0 || (!value.enabled && value.state == "off"))
    }
    private suspend fun <T> scoped(viewer: String, record: String, operation: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer); uuid(record)
        val result = operation()
        require(currentViewer() == viewer)
        Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }

    companion object {
        val STAGES = setOf("realtime", "quick", "final")
        private val JSON = "application/json".toMediaType()
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        fun validate(request: SummaryRequestDto) {
            require(request.operation in setOf("generate", "regenerate", "retry") && request.stage in STAGES && request.expectedRevision > 0)
            request.expectedJobId?.let(::uuid)
            require((request.expectedJobId == null) == (request.expectedAttempt == null))
            require(request.expectedAttempt == null || request.expectedAttempt > 0)
            require(request.operation != "retry" || request.expectedJobId != null)
        }
        fun validate(request: SummaryAutomationRequestDto) { require(request.expectedRevision >= 0) }
    }
}
