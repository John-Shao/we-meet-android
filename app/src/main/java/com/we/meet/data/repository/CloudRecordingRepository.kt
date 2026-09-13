package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.CloudRecordingApi
import com.we.meet.data.api.dto.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** An accepted command acknowledges reservation, not a running or saved recording. */
class CloudRecordingRepository(private val api: CloudRecordingApi, private val currentViewer: () -> String?) {
    suspend fun state(viewer: String, room: String, sid: String) = scoped(viewer) {
        OnlineCaptureRepository.source(room, sid)
        api.state(room, sid).also { validateState(it, room, sid) }
    }

    suspend fun control(viewer: String, key: String, request: CloudRecordingRequestDto) = scoped(viewer) {
        uuid(key); validate(request)
        val body = requireNotNull(requestAdapter.toJsonValue(request) as? Map<*, *>) + ("key" to key)
        api.control(jsonAdapter.toJson(body).toRequestBody("application/json".toMediaType())).also { receipt ->
            validateState(receipt.current, request.roomId, request.livekitRoomSid)
            val command = receipt.command
            uuid(command.id); require(command.key == key && command.sessionId == receipt.current.source.sessionId)
            require(command.payload == CloudRecordingPayloadDto(request.operation, request.expectedRecordingId))
            require(command.state in commandStates && command.errorCode.length <= 64)
            row(command.result, command.sessionId)
            if (request.operation == "start") require(command.result.status == "initiated" && command.result.id != request.expectedRecordingId)
            else require(command.result.status == "active" && command.result.id == request.expectedRecordingId)
        }
    }

    private suspend fun <T> scoped(viewer: String, task: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = task(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }

    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(CloudRecordingRequestDto::class.java).serializeNulls()
        private val jsonAdapter = moshi.adapter(Any::class.java).serializeNulls()
        private val recordingStates = setOf("initiated", "active", "stopped", "saved", "aborted", "failed_to_start", "failed_to_stop", "notification_succeeded")
        private val pendingStates = setOf("accepted", "running", "unknown")
        private val commandStates = pendingStates + setOf("succeeded", "failed")
        private val busyStates = setOf("initiated", "active", "failed_to_stop")
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }

        fun validate(request: CloudRecordingRequestDto) {
            OnlineCaptureRepository.source(request.roomId, request.livekitRoomSid)
            require(request.operation in setOf("start", "stop"))
            request.expectedRecordingId?.let(::uuid)
            require(request.operation != "stop" || request.expectedRecordingId != null)
        }

        private fun row(value: CloudRecordingDto, session: String) {
            uuid(value.id); require(value.sessionId == session && value.mode == "screen_recording")
            require(value.status in recordingStates); OffsetDateTime.parse(value.createdAt)
        }

        private fun validateState(value: CloudRecordingStateDto, room: String, sid: String) {
            require(value.source.roomId == room && value.source.livekitRoomSid == sid)
            uuid(value.source.sessionId); value.current?.let { row(it, value.source.sessionId) }
            value.pendingOperation?.let {
                uuid(it.id); require(value.current != null && it.operation in setOf("start", "stop") && it.state in pendingStates && it.errorCode.length <= 64)
            }
            require(!value.canStart || value.available && !value.blocked && value.pendingOperation == null && value.current?.status !in busyStates)
            require(!value.canStop || value.current?.status == "active" && value.pendingOperation == null)
            require(!(value.canStart && value.canStop))
        }
    }
}
