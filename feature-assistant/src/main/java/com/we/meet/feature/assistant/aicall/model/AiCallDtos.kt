package com.we.meet.feature.assistant.aicall.model

import com.squareup.moshi.JsonClass

// ---- AI agent catalog (GET /api/v1.0/rooms/ai-agent-config/) ----
// we-meet's profile-based contract: each profile carries its own voice list
// (voices have a UUID `id`), and prompts are addressed by UUID `id`.

@JsonClass(generateAdapter = true)
data class AiVoiceDto(
    val id: String,
    val value: String,
    val label: String? = null,
)

@JsonClass(generateAdapter = true)
data class AiProfileDto(
    val code: String,
    val display_name: String? = null,
    val agent_type: String? = null,
    val model_code: String? = null,
    val voices: List<AiVoiceDto> = emptyList(),
    val default_voice_id: String? = null,
    // No profile-level default prompt: model and prompt are decoupled
    // server-side. Prompt resolution falls back to user preference, then
    // to none.
)

@JsonClass(generateAdapter = true)
data class AiPromptDto(
    val id: String,
    val label: String,
    val content: String? = null,
)

@JsonClass(generateAdapter = true)
data class AiAgentConfigResponse(
    val profiles: List<AiProfileDto> = emptyList(),
    val prompts: List<AiPromptDto> = emptyList(),
) {
    /** The same Qwen 3.8 profile handles microphone and camera input. */
    fun callProfile(): AiProfileDto? = profiles.firstOrNull {
        it.model_code == "aliyun/qwen3.8-omni-flash-realtime"
    }

    fun resolveSelection(selection: AiCallSelection): AiCallSelection {
        val profile = callProfile()
        val voices = profile?.voices.orEmpty()
        return selection.copy(
            voiceId = selection.voiceId?.takeIf { id -> voices.any { it.id == id } }
                ?: profile?.default_voice_id?.takeIf { id -> voices.any { it.id == id } }
                ?: voices.firstOrNull()?.id,
            promptId = selection.promptId?.takeIf { id -> prompts.any { it.id == id } },
            sceneId = com.we.meet.feature.assistant.scenes.AssistantScene.find(selection.sceneId)?.id,
        )
    }
}

// SDP exchange is authenticated with the user's normal application session.
@JsonClass(generateAdapter = true)
data class AiCallOffer(
    val sdp: String,
    val profile_code: String,
    val voice_id: String? = null,
    val prompt_id: String? = null,
)

@JsonClass(generateAdapter = true)
data class AiCallAnswer(
    val sdp: String,
    val voice: String,
    val instructions: String,
) {
    fun forScene(id: String?): AiCallAnswer =
        com.we.meet.feature.assistant.scenes.AssistantScene.find(id)?.let { copy(instructions = it.instructions) } ?: this
}
