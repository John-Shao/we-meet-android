package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.OnlineCaptureApi
import com.we.meet.data.api.OnlineCaptureNoticeApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OnlineCaptureRepository(private val api: OnlineCaptureApi, private val currentViewer: () -> String?) {
    suspend fun state(viewer: String, room: String, sid: String) = scoped(viewer) {
        source(room, sid); api.state(room, sid).also { it.current?.let(::run) }
    }
    suspend fun control(viewer: String, key: String, request: OnlineCaptureRequestDto) = scoped(viewer) {
        uuid(key); validate(request)
        val value = requireNotNull(requestAdapter.toJsonValue(request) as? Map<*, *>) + ("key" to key)
        api.control(jsonAdapter.toJson(value).toRequestBody("application/json".toMediaType())).also {
            run(it.result); it.current?.let(::run)
            require(it.current == null || it.current.recordId == it.result.recordId)
            if (request.operation == "start") require(it.result.state == "starting" && it.result.id != request.expectedRunId)
            else require(it.result.id == request.expectedRunId && it.result.state in setOf("stopping", "stopped", "incomplete"))
        }
    }
    private suspend fun <T> scoped(viewer: String, task: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = task(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(OnlineCaptureRequestDto::class.java).serializeNulls()
        private val jsonAdapter = moshi.adapter(Any::class.java).serializeNulls()
        val states = setOf("starting", "recording", "stopping", "stopped", "incomplete")
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        fun source(room: String, sid: String) { uuid(room); require(sid.matches(Regex("RM_[A-Za-z0-9_-]{1,61}"))) }
        fun validate(value: OnlineCaptureRequestDto) {
            source(value.roomId, value.livekitRoomSid); require(value.operation in setOf("start", "stop"))
            value.expectedRunId?.let(::uuid); require(value.operation != "stop" || value.expectedRunId != null)
        }
        private fun run(value: OnlineCaptureRunDto) {
            uuid(value.id); uuid(value.recordId); require(value.state in states && value.coverage == "unverified" && value.errorCode.length <= 128)
            val start = value.startedAt?.let(OffsetDateTime::parse)
            val end = value.endedAt?.let(OffsetDateTime::parse)
            require(start == null || end == null || !end.isBefore(start))
        }
    }
}

class OnlineCaptureNoticeRepository(private val api: OnlineCaptureNoticeApi) {
    suspend fun notice(room: String, sid: String, token: String, isCurrentSource: () -> Boolean): Result<OnlineCaptureNoticeDto> = try {
        OnlineCaptureRepository.source(room, sid)
        require(token.length in 1..16384 && token.none { it.isWhitespace() } && isCurrentSource())
        val result = api.notice(room, sid, "Bearer $token")
        require(isCurrentSource() && (result.state == "off" || result.state in OnlineCaptureRepository.states))
        Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
}
