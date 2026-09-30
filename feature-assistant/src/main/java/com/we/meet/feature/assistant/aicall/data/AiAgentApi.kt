package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Uses the host's authenticated HTTP client; provider keys never reach Android. */
interface AiAgentApi {
    @GET("api/v1.0/rooms/ai-agent-config/")
    suspend fun fetchConfig(): AiAgentConfigResponse

    @POST("api/v1.0/ai-call/session/")
    suspend fun exchangeOffer(@Body offer: AiCallOffer): AiCallAnswer
}
