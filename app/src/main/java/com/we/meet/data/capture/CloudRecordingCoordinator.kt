package com.we.meet.data.capture

import com.we.meet.data.api.dto.CloudRecordingRequestDto
import com.we.meet.data.api.dto.CloudRecordingReceiptDto
import com.we.meet.data.repository.CloudRecordingRepository
import com.we.meet.data.repository.OnlineCaptureRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class CloudRecordingCoordinator(private val viewer: String, private val store: MeetingIntentStore, private val repository: CloudRecordingRepository) {
    private val lock = Mutex()
    suspend fun pending(room: String, sid: String): MeetingIntent? = withContext(Dispatchers.IO) { store.get(MeetingIntentKind.CLOUD_RECORDING, resource(room, sid)) }

    suspend fun control(request: CloudRecordingRequestDto): CloudRecordingReceiptDto {
        CloudRecordingRepository.validate(request)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                val address = resource(request.roomId, request.livekitRoomSid)
                val intent = store.getOrCreate(MeetingIntentKind.CLOUD_RECORDING, address, CloudRecordingRepository.requestAdapter.toJson(request))
                try {
                    val original = requireNotNull(CloudRecordingRepository.requestAdapter.fromJson(intent.body))
                    require(original.roomId == request.roomId && original.livekitRoomSid == request.livekitRoomSid)
                    val receipt = repository.control(viewer, intent.key, original).getOrThrow()
                    store.resolve(MeetingIntentKind.CLOUD_RECORDING, address, intent)
                    receipt
                } catch (error: HttpException) {
                    if (error.code() in setOf(400, 409, 422)) store.resolve(MeetingIntentKind.CLOUD_RECORDING, address, intent)
                    throw error
                }
            }
        }
    }

    companion object {
        private fun resource(room: String, sid: String): String {
            OnlineCaptureRepository.source(room, sid)
            return UUID.nameUUIDFromBytes("cloud-recording/v1/$room/$sid".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}
