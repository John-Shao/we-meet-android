package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiHistoryConversationDto
import com.we.meet.feature.assistant.aicall.model.AiHistoryListDto
import com.we.meet.feature.assistant.aicall.model.AiHistoryUpsertDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Text-only cross-client history sync. Media never leaves the device in phase 1. */
interface AiHistoryApi {
    @GET("api/v1.0/ai-call/conversations/")
    suspend fun list(@Query("include_messages") includeMessages: Int = 1): AiHistoryListDto

    @POST("api/v1.0/ai-call/conversations/")
    suspend fun upsert(@Body body: AiHistoryUpsertDto): AiHistoryConversationDto

    @DELETE("api/v1.0/ai-call/conversations/{id}/")
    suspend fun delete(@Path("id") id: String)

    @DELETE("api/v1.0/ai-call/conversations/by-client/{clientId}/")
    suspend fun deleteByClient(@Path("clientId") clientId: String)
}
