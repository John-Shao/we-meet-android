package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.StartAgentRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * we-meet AI-agent endpoints.
 *
 * `start`/`stop` are authenticated by the room's **LiveKit token** (not the
 * user's OIDC bearer): the backend's LiveKitTokenAuthentication reads it from
 * the Authorization header. We therefore set Authorization explicitly AND
 * send the host's [com.we.meet.data.auth.AuthInterceptor] skip marker
 * ("No-Auth") so it doesn't overwrite our header with the OIDC token.
 */
interface AiAgentApi {
    // Public catalog; the OIDC bearer the host attaches is harmless here.
    @GET("api/v1.0/rooms/ai-agent-config/")
    suspend fun fetchConfig(): AiAgentConfigResponse

    @POST("api/v1.0/rooms/{roomId}/start-ai-agent/")
    suspend fun startAgent(
        @Path("roomId") roomId: String,
        @Header(HEADER_SKIP_HOST_AUTH) skip: String = "1",
        @Header("Authorization") authorization: String,
        @Body body: StartAgentRequest,
    )

    @POST("api/v1.0/rooms/{roomId}/stop-ai-agent/")
    suspend fun stopAgent(
        @Path("roomId") roomId: String,
        @Header(HEADER_SKIP_HOST_AUTH) skip: String = "1",
        @Header("Authorization") authorization: String,
    )

    companion object {
        /** Must match com.we.meet.data.auth.AuthInterceptor.NO_AUTH on the host. */
        const val HEADER_SKIP_HOST_AUTH = "No-Auth"
    }
}
