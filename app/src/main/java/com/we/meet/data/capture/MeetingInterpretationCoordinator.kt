package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingInterpretationRepository
import com.we.meet.data.repository.MeetingTranslationRepository
import com.we.meet.data.repository.OnlineCaptureRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class MeetingInterpretationCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: MeetingInterpretationRepository) {
    private val lock = Mutex()
    suspend fun pendingChannel(room: String, sid: String) = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.INTERPRETATION_CHANNEL, resource(room, sid)) }
    suspend fun pendingListen(room: String, sid: String, localSid: String) = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.INTERPRETATION_LISTEN, listenerResource(room, sid, localSid)) }
    suspend fun control(request: InterpretationChannelRequestDto): InterpretationChannelReceiptDto {
        MeetingInterpretationRepository.validate(request)
        return apply(MeetingIntentKind.INTERPRETATION_CHANNEL, resource(request.roomId, request.livekitRoomSid), MeetingInterpretationRepository.channelAdapter.toJson(request)) { intent ->
            val original = requireNotNull(MeetingInterpretationRepository.channelAdapter.fromJson(intent.body))
            require(original.roomId == request.roomId && original.livekitRoomSid == request.livekitRoomSid)
            repository.control(viewer, intent.key, original).getOrThrow()
        }
    }
    suspend fun subscribe(localSid: String, request: InterpretationListenRequestDto): InterpretationListenReceiptDto {
        MeetingInterpretationRepository.validate(request)
        val address = listenerResource(request.roomId, request.livekitRoomSid, localSid)
        return apply(MeetingIntentKind.INTERPRETATION_LISTEN, address, MeetingInterpretationRepository.listenIntentAdapter.toJson(InterpretationListenIntentDto(localSid, request))) { intent ->
            val original = requireNotNull(MeetingInterpretationRepository.listenIntentAdapter.fromJson(intent.body))
            require(original.localSid == localSid && original.request.roomId == request.roomId && original.request.livekitRoomSid == request.livekitRoomSid)
            repository.subscribe(viewer, intent.key, original.request).getOrThrow()
        }
    }
    private suspend fun <T> apply(kind: MeetingIntentKind, address: String, body: String, dispatch: suspend (MeetingIntent) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val intent = store.getOrCreate(kind, address, body)
            try { dispatch(intent).also { store.resolve(kind, address, intent) } }
            catch (error: HttpException) {
                if (error.code() in setOf(400, 409, 422)) store.resolve(kind, address, intent)
                throw error
            }
        }
    }
    private fun resource(room: String, sid: String): String {
        OnlineCaptureRepository.source(room, sid)
        return UUID.nameUUIDFromBytes("interpretation-channel/v1/$room/$sid".toByteArray(Charsets.UTF_8)).toString()
    }
    private fun listenerResource(room: String, sid: String, localSid: String): String {
        OnlineCaptureRepository.source(room, sid); MeetingTranslationRepository.participant(localSid)
        return UUID.nameUUIDFromBytes("interpretation-listen/v1/$room/$sid/$localSid".toByteArray(Charsets.UTF_8)).toString()
    }
}
