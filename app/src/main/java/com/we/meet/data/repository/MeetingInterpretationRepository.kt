package com.we.meet.data.repository

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingInterpretationApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MeetingInterpretationRepository(private val api: MeetingInterpretationApi, private val currentViewer: () -> String?) {
    suspend fun state(viewer: String, room: String, sid: String) = scoped(viewer) {
        OnlineCaptureRepository.source(room, sid)
        api.state(room, sid).also { value ->
            require(value.listenerLeaseSeconds == 20 && value.languages.isNotEmpty() && value.languages.distinct() == value.languages && value.languages.all { it in languages })
            require(value.channels.size <= 2 && value.channels.map { it.target }.distinct().size == value.channels.size && value.channels.map { it.id }.distinct().size == value.channels.size)
            value.channels.forEach { channel(it); require(it.target in value.languages) }
            require(value.connections.size <= 100 && value.connections.map { it.id }.distinct().size == value.connections.size && value.connections.map { it.participantSid }.distinct().size == value.connections.size)
            value.connections.forEach { uuid(it.id); MeetingTranslationRepository.participant(it.participantSid) }
            require(value.subscriptions.size <= value.connections.size && value.subscriptions.map { it.id }.distinct().size == value.subscriptions.size && value.subscriptions.map { it.participationId }.distinct().size == value.subscriptions.size)
            value.subscriptions.forEach { subscription(it); require(value.connections.any { connection -> connection.id == it.participationId }) }
        }
    }
    suspend fun control(viewer: String, key: String, request: InterpretationChannelRequestDto) = scoped(viewer) {
        uuid(key); validate(request)
        api.control(body(key, channelAdapter, request)).also {
            channel(it.result); require(it.result.target == request.target)
            if (request.operation == "start") {
                require(it.result.id != request.expectedChannelId && it.result.state == "prepared")
                require((it.result.archiveRecordId != null) == (request.saveTranslations == true))
            } else require(it.result.id == request.expectedChannelId && it.result.state in setOf("stopping", "stopped"))
        }
    }
    suspend fun subscribe(viewer: String, key: String, request: InterpretationListenRequestDto) = scoped(viewer) {
        uuid(key); validate(request)
        api.subscribe(body(key, listenAdapter, request)).also {
            subscription(it.result)
            require(it.result.channelId == request.channelId && it.result.participationId == request.participationId && it.result.revision == request.expectedRevision + 1)
            require(it.result.active == (request.operation == "join"))
            // This may be a historical receipt. Its frozen lease is never a new playback grant.
        }
    }
    suspend fun renew(viewer: String, subscriptionId: String, request: InterpretationRenewRequestDto) = scoped(viewer) {
        uuid(subscriptionId); OnlineCaptureRepository.source(request.roomId, request.livekitRoomSid)
        uuid(request.participationId); uuid(request.channelId); require(request.revision in 1..MAX_REVISION)
        api.renew(request).also {
            subscription(it)
            require(it.id == subscriptionId && it.channelId == request.channelId && it.participationId == request.participationId && it.revision == request.revision && it.active)
        }
    }
    private suspend fun <T> scoped(viewer: String, task: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = task(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private const val MAX_REVISION = 9007199254740991L
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val channelAdapter = moshi.adapter(InterpretationChannelRequestDto::class.java).serializeNulls()
        val listenAdapter = moshi.adapter(InterpretationListenRequestDto::class.java)
        val listenIntentAdapter = moshi.adapter(InterpretationListenIntentDto::class.java)
        private val jsonAdapter = moshi.adapter(Any::class.java).serializeNulls()
        val languages = setOf("zh", "en")
        val activeStates = setOf("prepared", "starting", "translating", "stopping")
        private fun uuid(value: String) = MeetingTranslationRepository.uuid(value)
        private fun <T> body(key: String, adapter: JsonAdapter<T>, request: T) = jsonAdapter.toJson(
            requireNotNull(adapter.toJsonValue(request) as? Map<*, *>).filter { it.value != null || it.key == "expected_channel_id" } + ("key" to key)
        ).toRequestBody("application/json".toMediaType())
        fun validate(request: InterpretationChannelRequestDto) {
            OnlineCaptureRepository.source(request.roomId, request.livekitRoomSid); require(request.target in languages)
            request.expectedChannelId?.let(::uuid)
            require(request.operation == "start" || request.operation == "stop" && request.expectedChannelId != null && request.saveTranslations == null)
        }
        fun validate(request: InterpretationListenRequestDto) {
            OnlineCaptureRepository.source(request.roomId, request.livekitRoomSid); uuid(request.participationId); uuid(request.channelId)
            require(request.operation in setOf("join", "leave") && request.expectedRevision in 0 until MAX_REVISION)
            require(request.operation != "leave" || request.expectedRevision > 0)
        }
        private fun channel(value: InterpretationChannelDto) {
            uuid(value.id); require(value.target in languages && value.generation in 1..MAX_REVISION && value.state in activeStates + setOf("stopped", "incomplete") && value.errorCode.length <= 64)
            value.archiveRecordId?.let(::uuid)
        }
        fun subscription(value: InterpretationSubscriptionDto) {
            uuid(value.id); uuid(value.channelId); uuid(value.participationId); OffsetDateTime.parse(value.expiresAt)
            require(value.revision in 1..MAX_REVISION && value.remainingLeaseSeconds.isFinite() && value.remainingLeaseSeconds in 0.0..20.0)
            require(value.active == (value.remainingLeaseSeconds > 0))
        }
        /** For fresh reads/renewals only. Do not call on frozen command receipts. */
        fun deadline(value: InterpretationSubscriptionDto, requestStartedAt: Long, now: Long): Long {
            if (runCatching { subscription(value) }.isFailure || !value.active || requestStartedAt !in 0..now || requestStartedAt > Long.MAX_VALUE - 20000) return 0
            return (requestStartedAt + (value.remainingLeaseSeconds * 1000).toLong()).takeIf { it > now } ?: 0
        }
    }
}
