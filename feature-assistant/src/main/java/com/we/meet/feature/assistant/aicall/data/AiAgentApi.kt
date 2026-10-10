package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Uses the host's authenticated HTTP client; provider keys never reach Android. */
interface AiAgentApi {
    @GET("api/v1.0/rooms/ai-agent-config/")
    suspend fun fetchConfig(): AiAgentConfigResponse

    @POST("api/v1.0/ai-call/session/")
    suspend fun exchangeOffer(@Body offer: AiCallOffer): AiCallAnswer
    @POST("api/v1.0/ai-call/photo/")
    suspend fun photoQa(@Body request: com.we.meet.feature.assistant.aicall.model.PhotoQaRequest): com.we.meet.feature.assistant.aicall.model.PhotoQaAnswer =
        error("Photo QA unavailable")
    @POST("api/v1.0/direct-ai/sessions/{id}/")
    suspend fun sessionLease(@Path("id") id: String, @Body operation: DirectAILeaseOperation) = Unit
}
