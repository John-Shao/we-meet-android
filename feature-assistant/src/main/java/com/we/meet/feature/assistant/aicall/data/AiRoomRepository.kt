package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.CreateRoomRequest
import com.we.meet.feature.assistant.aicall.model.CreateRoomResponse

class AiRoomRepository(private val api: RoomApi) {

    suspend fun createRoom(name: String): CreateRoomResponse =
        api.createRoom(CreateRoomRequest(name = name, access_level = "public"))

    suspend fun endRoom(roomId: String): Result<Unit> = runCatching {
        api.endRoom(roomId)
    }
}
