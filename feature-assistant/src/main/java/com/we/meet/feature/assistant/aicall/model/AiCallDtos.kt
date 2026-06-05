package com.we.meet.feature.assistant.aicall.model

import com.squareup.moshi.JsonClass

// ---- Room creation (POST /api/v1.0/rooms/) ----

@JsonClass(generateAdapter = true)
data class CreateRoomRequest(
    val name: String,
    val access_level: String = "public",
)

@JsonClass(generateAdapter = true)
data class LiveKitInfoDto(
    val url: String,
    val room: String?,
    val token: String,
)

@JsonClass(generateAdapter = true)
data class CreateRoomResponse(
    val id: String,
    val slug: String? = null,
    val livekit: LiveKitInfoDto? = null,
)

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
    val architecture: String? = null,
    val voices: List<AiVoiceDto> = emptyList(),
    val default_voice_id: String? = null,
    // No profile-level default prompt: model and prompt are decoupled
    // server-side. Prompt resolution falls back to user preference, then
    // to none.
) {
    val isOmni: Boolean get() = architecture.equals("omni", ignoreCase = true)

    fun mentions(needle: String): Boolean =
        code.contains(needle, ignoreCase = true) ||
            (display_name?.contains(needle, ignoreCase = true) == true)
}

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
    fun profile(code: String): AiProfileDto? = profiles.firstOrNull { it.code == code }

    /** Backend is the source of truth for profile codes — pick by feature
     *  rather than a hardcoded code so the client tolerates code drift
     *  (e.g. ``qwen`` vs ``qwen-omni-realtime``). */

    /** Video-capable realtime profile (Qwen-Omni family). */
    fun videoProfile(): AiProfileDto? =
        profiles.firstOrNull { it.mentions("qwen") }
            ?: profiles.firstOrNull { it.isOmni }

    /** Audio realtime profile (Doubao S2S family). */
    fun voiceProfile(): AiProfileDto? =
        profiles.firstOrNull { it.mentions("doubao") && it.mentions("s2s") }
            ?: profiles.firstOrNull { it.isOmni && !it.mentions("qwen") }
            ?: profiles.firstOrNull { it.isOmni }
}

// ---- Start agent (POST /api/v1.0/rooms/{id}/start-ai-agent/) ----
// Body is profile-based; request is authenticated by the room's LiveKit
// token in the Authorization header (see AiAgentApi).

@JsonClass(generateAdapter = true)
data class StartAgentRequest(
    val profile_code: String,
    val voice_id: String? = null,
    val prompt_id: String? = null,
)
