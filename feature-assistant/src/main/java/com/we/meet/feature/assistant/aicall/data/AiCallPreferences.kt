package com.we.meet.feature.assistant.aicall.data

import android.content.Context
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.aicall.model.AiCallVadMode
import com.we.meet.feature.assistant.aicall.model.AiCallVideoSettings

/** One set of preferences shared by microphone-only and camera-enabled calls. */
class AiCallPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("we_meet_ai_call_prefs", Context.MODE_PRIVATE)

    fun load(): AiCallSelection {
        // Promote existing validation preferences once; later manual fallback choices persist.
        if (!prefs.getBoolean("aoq_default_v1", false)) {
            prefs.edit().putString("call_transport", "AOQ").putBoolean("aoq_default_v1", true).apply()
        }
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
            transport = com.we.meet.feature.assistant.aicall.model.AiCallTransport.entries.firstOrNull { it.name == prefs.getString("call_transport", null) } ?: com.we.meet.feature.assistant.aicall.model.AiCallTransport.AOQ,
            sceneId = com.we.meet.feature.assistant.scenes.AssistantScene.find(prefs.getString("call_scene_id", null))?.id,
            vadMode = AiCallVadMode.fromStored(prefs.getString("call_vad_mode", null)),
            videoSettings = AiCallVideoSettings.fromStored(
                prefs.getInt("call_local_preview_fps", 15), prefs.getInt("call_model_upload_fps", 2)),
        )
    }

    fun save(selection: AiCallSelection) {
        prefs.edit().apply {
            putBoolean("unified_selection", true)
            putBoolean("aoq_default_v1", true)
            putString("call_voice_id", selection.voiceId)
            putString("call_prompt_id", selection.promptId)
            putString("call_scene_id", selection.sceneId)
            putString("call_transport", selection.transport.name)
            putString("call_vad_mode", selection.vadMode.wireValue)
            putInt("call_local_preview_fps", selection.videoSettings.localPreviewFps)
            putInt("call_model_upload_fps", selection.videoSettings.modelUploadFps)
            for (prefix in listOf("voice", "video")) {
                for (suffix in listOf("profile_code", "voice_id", "prompt_id")) {
                    remove("${prefix}_${suffix}")
                }
            }
        }.apply()
    }
}
