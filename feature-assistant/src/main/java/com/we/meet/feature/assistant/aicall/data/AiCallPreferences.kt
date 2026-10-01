package com.we.meet.feature.assistant.aicall.data

import android.content.Context
import com.we.meet.feature.assistant.aicall.model.AiCallSelection

/** One set of preferences shared by microphone-only and camera-enabled calls. */
class AiCallPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("we_meet_ai_call_prefs", Context.MODE_PRIVATE)

    fun load(): AiCallSelection {
        if (!prefs.getBoolean("unified_selection", false)) {
            // Prefer the previous Qwen/video settings. Catalog validation drops
            // incompatible voice IDs left by an older provider or model.
            val selection = AiCallSelection(
                voiceId = prefs.getString("video_voice_id", null)
                    ?: prefs.getString("voice_voice_id", null),
                promptId = if (prefs.contains("video_profile_code") || prefs.contains("video_voice_id")) {
                    prefs.getString("video_prompt_id", null)
                } else {
                    prefs.getString("video_prompt_id", null) ?: prefs.getString("voice_prompt_id", null)
                },
            )
            save(selection)
            return selection
        }
        return AiCallSelection(
            voiceId = prefs.getString("call_voice_id", null),
            promptId = prefs.getString("call_prompt_id", null),
            sceneId = com.we.meet.feature.assistant.scenes.AssistantScene.find(prefs.getString("call_scene_id", null))?.id,
        )
    }

    fun save(selection: AiCallSelection) {
        prefs.edit().apply {
            putBoolean("unified_selection", true)
            putString("call_voice_id", selection.voiceId)
            putString("call_prompt_id", selection.promptId)
            putString("call_scene_id", selection.sceneId)
            for (prefix in listOf("voice", "video")) {
                for (suffix in listOf("profile_code", "voice_id", "prompt_id")) {
                    remove("${prefix}_${suffix}")
                }
            }
        }.apply()
    }
}
