package com.we.meet.feature.assistant.aicall.data

import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.StartAgentRequest

class AiAgentRepository(private val api: AiAgentApi) {

    suspend fun fetchConfig(): AiAgentConfigResponse = api.fetchConfig()

    /**
     * Dispatch the agent. [livekitToken] authenticates the request (LiveKit
     * token in the Authorization header); the profile/voice/prompt select the
     * model + persona server-side.
     */
    suspend fun startAgent(
        roomId: String,
        livekitToken: String,
        profileCode: String,
        voiceId: String?,
        promptId: String?,
    ) {
        api.startAgent(
            roomId = roomId,
            authorization = "Bearer $livekitToken",
            body = StartAgentRequest(
                profile_code = profileCode,
                voice_id = voiceId,
                prompt_id = promptId,
            ),
        )
    }

    suspend fun stopAgent(roomId: String, livekitToken: String): Result<Unit> = runCatching {
        api.stopAgent(roomId = roomId, authorization = "Bearer $livekitToken")
    }
}
