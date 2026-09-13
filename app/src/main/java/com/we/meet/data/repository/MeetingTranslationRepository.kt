package com.we.meet.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.MeetingTranslationApi
import com.we.meet.data.api.dto.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MeetingTranslationRepository(private val api: MeetingTranslationApi, private val currentViewer: () -> String?) {
    suspend fun state(viewer: String, room: String, sid: String) = scoped(viewer) {
        OnlineCaptureRepository.source(room, sid)
        api.state(room, sid).also { value ->
            require(value.languages.isNotEmpty() && value.languages.distinct() == value.languages && value.languages.all { it in languages })
            require(value.sources.size <= 100 && value.sources.map { it.id }.distinct().size == value.sources.size && value.sources.map { it.participantSid }.distinct().size == value.sources.size)
            value.sources.forEach { uuid(it.id); participant(it.participantSid) }
            value.current?.let(::validateRun)
        }
    }
    suspend fun control(viewer: String, key: String, request: PrivateTranslationRequestDto) = scoped(viewer) {
        uuid(key); validate(request)
        // Only expected_run_id is explicitly null on the wire. Stop must omit all start options.
        val fields = requireNotNull(requestAdapter.toJsonValue(request) as? Map<*, *>).filter { it.value != null || it.key == "expected_run_id" } + ("key" to key)
        api.control(jsonAdapter.toJson(fields).toRequestBody("application/json".toMediaType())).also { receipt ->
            val result = receipt.result
            validateRun(result); receipt.current?.let { current ->
                validateRun(current); require(current.generation >= result.generation)
                require((current.id == result.id) == (current.generation == result.generation))
                if (current.id == result.id) require(current.configuration == result.configuration && (current.sourceParticipantSid == result.sourceParticipantSid || current.sourceParticipantSid == null))
            }
            if (request.operation == "start") {
                require(result.id != request.expectedRunId && result.state == "starting" && result.sourceParticipantSid != null)
                with(result.configuration) {
                    require(source == request.source && target == request.target && mode == request.mode && audio == request.audio)
                    require((archiveRecordId != null) == (request.saveTranslations == true))
                }
            } else require(result.id == request.expectedRunId && result.state in setOf("stopping", "stopped", "incomplete"))
        }
    }
    private suspend fun <T> scoped(viewer: String, task: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = task(); require(currentViewer() == viewer); Result.success(result)
    } catch (canceled: CancellationException) { throw canceled }
    catch (error: Exception) { Result.failure(error) }
    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(PrivateTranslationRequestDto::class.java).serializeNulls()
        private val jsonAdapter = moshi.adapter(Any::class.java).serializeNulls()
        val languages = setOf("zh", "en")
        val modes = setOf("simultaneous", "push_to_talk")
        val activeStates = setOf("starting", "translating", "stopping")
        fun uuid(value: String) { require(UUID.fromString(value).toString() == value && value != "00000000-0000-0000-0000-000000000000") }
        fun participant(value: String) { require(value.matches(Regex("PA_[A-Za-z0-9_-]{1,61}"))) }
        fun validate(request: PrivateTranslationRequestDto) {
            OnlineCaptureRepository.source(request.roomId, request.livekitRoomSid)
            request.expectedRunId?.let(::uuid)
            if (request.operation == "start") {
                uuid(requireNotNull(request.sourceParticipationId))
                require(request.source in languages && request.target in languages && request.source != request.target)
                require(request.mode in modes && request.audio != null)
            } else {
                require(request.operation == "stop" && request.expectedRunId != null)
                require(listOf(request.sourceParticipationId, request.source, request.target, request.mode, request.audio, request.saveTranslations).all { it == null })
            }
        }
        fun validateRun(value: PrivateTranslationRunDto) {
            uuid(value.id); require(value.generation in 1..9007199254740991L && value.state in activeStates + setOf("stopped", "incomplete") && value.errorCode.length <= 128)
            value.sourceParticipantSid?.let(::participant)
            with(value.configuration) {
                require(source in languages && target in languages && source != target && mode in modes)
                require(model == "qwen3.5-livetranslate-flash-realtime" && scope == "controller_only")
                archiveRecordId?.let(::uuid)
            }
        }
    }
}
