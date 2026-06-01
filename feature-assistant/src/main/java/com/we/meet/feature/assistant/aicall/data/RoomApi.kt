package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.CreateRoomRequest
import com.we.meet.feature.assistant.aicall.model.CreateRoomResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface RoomApi {
    @POST("api/v1.0/rooms/")
    suspend fun createRoom(@Body body: CreateRoomRequest): CreateRoomResponse

    @POST("api/v1.0/rooms/{id}/end/")
    suspend fun endRoom(@Path("id") id: String)
}
