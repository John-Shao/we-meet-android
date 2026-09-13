package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CaptureTranslationApi
import com.we.meet.data.api.dto.*
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Lease and ticket are transient credentials, never fields in an intent or a URL. */
data class CaptureTranslationSource(val viewer: String, val capture: String, val record: String, val device: String, val lease: String) {
    override fun toString() = "CaptureTranslationSource(<private>)"
}

class CaptureTranslationRepository(private val api: CaptureTranslationApi, private val currentViewer: () -> String?) {
    suspend fun state(source: CaptureTranslationSource) = scoped(source.viewer) {
        validateSource(source)
        api.state(source.capture).also { validateState(it, source.capture, source.record) }
    }
    suspend fun control(source: CaptureTranslationSource, key: String, request: CaptureTranslationRequestDto) = scoped(source.viewer) {
        validateSource(source); uuid(key); validate(request, source.device)
        val payload = requireNotNull(requestAdapter.toJsonValue(request) as? Map<*, *>) + ("key" to key)
        api.control(source.capture, source.lease, json.toJson(payload).toRequestBody("application/json".toMediaType())).also { receipt ->
            validateState(receipt.current, source.capture, source.record)
            val command = receipt.command
            require(command.key == key && command.captureId == source.capture && command.payload == request)
            validateRun(command.result, source.capture)
            with(command.result) {
                if (request.operation == "start") require(status == "starting" && id != request.expectedRunId && sourceRevision == request.expectedRevision && configuration.choice() == request.configuration)
                else require(id == request.expectedRunId && status in setOf("stopping", "stopped"))
            }
            val current = requireNotNull(receipt.current.current)
            require(current.generation >= command.result.generation)
            if (current.id == command.result.id) require(current.generation == command.result.generation && current.sourceRevision == command.result.sourceRevision && current.configuration == command.result.configuration)
            else require(current.generation > command.result.generation)
        }
    }
    suspend fun ticket(source: CaptureTranslationSource, run: CaptureTranslationRunDto) = scoped(source.viewer) {
        validateSource(source); validateRun(run, source.capture); require(run.status == "starting")
        api.ticket(source.capture, source.lease, CaptureTranslationTicketRequestDto(source.device, run.id, run.generation)).also { validateTicket(it, source, run) }
    }
    suspend fun archives(viewer: String, capture: String, record: String, after: String? = null) = scoped(viewer) {
        uuid(capture); uuid(record); cursor(after)
        api.archives(capture, after).also { page ->
            require(page.captureId == capture && page.recordId == record && page.results.size <= 30)
            cursor(page.nextCursor); require(page.results.map { it.id }.distinct().size == page.results.size)
            page.results.forEach { validateArchive(it, capture) }
        }
    }
    suspend fun segments(viewer: String, capture: String, record: String, archive: CaptureTranslationArchiveDto, after: String? = null) = scoped(viewer) {
        uuid(capture); uuid(record); validateArchive(archive, capture); cursor(after)
        api.segments(capture, archive.id, after).also { page ->
            require(page.captureId == capture && page.recordId == record && page.archiveId == archive.id && page.runId == archive.runId && page.generation == archive.generation && page.archiveStatus in archiveStates && page.results.size <= 50)
            cursor(page.nextCursor); require(page.results.map { it.id }.distinct().size == page.results.size)
            page.results.forEachIndexed { index, row ->
                uuid(row.id); require(row.sequence in 1..20000 && (index == 0 || row.sequence > page.results[index - 1].sequence))
                require(row.sourceCaptureId == capture && (row.direction == "forward" || row.direction == "reverse" && archive.configuration.mode == "push_to_talk"))
                require(row.target == if (row.direction == "reverse") archive.configuration.sourceLanguage else archive.configuration.targetLanguage)
                require(row.text.isNotBlank() && row.text.length <= 20000 && row.timingBasis == "delivery" && row.originalId == null)
                OffsetDateTime.parse(row.receivedAt)
            }
        }
    }
    private suspend fun <T> scoped(viewer: String, action: suspend () -> T): Result<T> = try {
        uuid(viewer); check(currentViewer() == viewer)
        val value = action(); check(currentViewer() == viewer); Result.success(value)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }

    companion object {
        const val MODEL = "qwen3.5-livetranslate-flash-realtime"
        val activeStates = setOf("starting", "translating", "stopping")
        private val archiveStates = setOf("capturing", "complete", "incomplete")
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(CaptureTranslationRequestDto::class.java).serializeNulls().failOnUnknown()
        val configurationAdapter = moshi.adapter(CaptureTranslationConfigDto::class.java).failOnUnknown()
        private val json = moshi.adapter(Any::class.java).serializeNulls()
        fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        fun validateSource(source: CaptureTranslationSource) {
            listOf(source.viewer, source.capture, source.record, source.lease).forEach(::uuid)
            require(source.device.isNotBlank() && source.device.length <= 128)
        }
        fun validateChoice(value: CaptureTranslationChoiceDto) {
            require(value.sourceLanguage in setOf("zh", "en") && value.targetLanguage in setOf("zh", "en") && value.sourceLanguage != value.targetLanguage)
            require(value.mode in setOf("simultaneous", "push_to_talk"))
        }
        fun validateConfiguration(value: CaptureTranslationConfigDto) {
            validateChoice(value.choice()); require(value.model == MODEL && value.region in setOf("cn-beijing", "ap-southeast-1"))
        }
        fun validate(value: CaptureTranslationRequestDto, device: String) {
            require(value.deviceId == device && device.isNotBlank() && device.length <= 128 && value.expectedRevision in 1..9007199254740991L)
            value.expectedRunId?.let(::uuid)
            if (value.operation == "start") validateChoice(requireNotNull(value.configuration))
            else require(value.operation == "stop" && value.configuration == null && value.expectedRunId != null)
        }
        fun validateRun(value: CaptureTranslationRunDto, capture: String) {
            uuid(value.id); require(value.captureId == capture && value.generation in 1..9007199254740991L && value.sourceRevision in 1..9007199254740991L)
            validateConfiguration(value.configuration); require(value.status in activeStates + setOf("stopped", "incomplete"))
            OffsetDateTime.parse(value.deadline); value.endedAt?.let { OffsetDateTime.parse(it) }
            require((value.endedAt == null) == (value.status in activeStates) && value.errorCode.length <= 128)
        }
        fun validateState(value: CaptureTranslationStateDto, capture: String, record: String) {
            require(value.source.captureId == capture && value.source.recordId == record && value.source.revision in 1..9007199254740991L)
            require(value.source.status in setOf("preparing", "recording", "paused", "interrupted", "stopping", "stopped"))
            value.current?.let { validateRun(it, capture); require(it.sourceRevision <= value.source.revision); if (it.status in activeStates) require(it.sourceRevision == value.source.revision && value.source.status == "recording") }
            require(!value.canStart || value.available && value.source.status == "recording" && value.current?.status !in activeStates)
            require(!value.canStop || value.current?.status in setOf("starting", "translating"))
            require(!(value.canStart && value.canStop))
        }
        fun validateTicket(value: CaptureTranslationTicketDto, source: CaptureTranslationSource, run: CaptureTranslationRunDto, now: Long = System.currentTimeMillis()) {
            val url = URI(value.gatewayUrl)
            require(url.scheme == "wss" && !url.host.isNullOrBlank() && url.rawPath == "/capture-translation" && url.rawQuery == null && url.rawFragment == null && url.rawUserInfo == null && (url.port == -1 || url.port in 1..65535))
            require(value.ticket.length in 1..4096)
            val expires = OffsetDateTime.parse(value.expiresAt).toInstant().toEpochMilli()
            require(expires > now && expires <= now + 32000)
            require(value.source == CaptureTranslationTicketSourceDto(run.id, source.capture, source.viewer, source.device, run.generation, run.sourceRevision))
        }
        private fun cursor(value: String?) { require(value == null || value.length in 1..2048 && value.all { !it.isWhitespace() && it.code >= 32 }) }
        private fun validateArchive(value: CaptureTranslationArchiveDto, capture: String) {
            uuid(value.id); uuid(value.runId); require(value.captureId == capture && value.generation in 1..9007199254740991L && value.status in archiveStates && value.segmentCount in 0..20000)
            validateConfiguration(value.configuration); require(value.configuration.saveTranslations); OffsetDateTime.parse(value.createdAt)
        }
    }
}
