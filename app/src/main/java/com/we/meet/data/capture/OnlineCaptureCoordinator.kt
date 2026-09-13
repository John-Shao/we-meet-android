package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.OnlineCaptureRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Same room reused in another LiveKit occurrence has an independent encrypted intent address. */
class OnlineCaptureCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: OnlineCaptureRepository) {
    private val lock = Mutex()
    suspend fun pending(room: String, sid: String): MeetingIntent? = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.ONLINE_CAPTURE, resource(room, sid)) }
    suspend fun control(request: OnlineCaptureRequestDto): OnlineCaptureReceiptDto {
        OnlineCaptureRepository.validate(request)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val address = resource(request.roomId, request.livekitRoomSid)
                val intent = store.getOrCreate(MeetingIntentKind.ONLINE_CAPTURE, address, OnlineCaptureRepository.requestAdapter.toJson(request))
                try {
                    val original = requireNotNull(OnlineCaptureRepository.requestAdapter.fromJson(intent.body))
                    require(original.roomId == request.roomId && original.livekitRoomSid == request.livekitRoomSid)
                    val result = repository.control(viewer, intent.key, original).getOrThrow()
                    store.resolve(MeetingIntentKind.ONLINE_CAPTURE, address, intent); result
                } catch (error: HttpException) {
                    if (error.code() in setOf(400, 409, 422)) store.resolve(MeetingIntentKind.ONLINE_CAPTURE, address, intent)
                    throw error
                }
            }
        }
    }
    companion object {
        private fun resource(room: String, sid: String): String {
            OnlineCaptureRepository.source(room, sid)
            return UUID.nameUUIDFromBytes("online-capture/v1/$room/$sid".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
