package com.we.meet.feature.assistant.aicall.data

import android.content.Context
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiModeSelection

/**
 * Persists the user's per-mode AI agent configuration: which (profile,
 * voice, prompt) triple they picked for voice calls vs video calls.
 *
 * Legacy keys ``voice_index`` / ``prompt_label`` (one-dimensional pre-tab
 * UI) are silently ignored when loading and not written back — they decay
 * out on first save.
 */
class AiCallPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(mode: AiCallMode): AiModeSelection {
        val (k1, k2, k3) = keys(mode)
        return AiModeSelection(
            profileCode = prefs.getString(k1, null),
            voiceId = prefs.getString(k2, null),
            promptId = prefs.getString(k3, null),
        )
    }

    fun save(mode: AiCallMode, selection: AiModeSelection) {
        val (k1, k2, k3) = keys(mode)
        prefs.edit().apply {
            putOrRemove(k1, selection.profileCode)
            putOrRemove(k2, selection.voiceId)
            putOrRemove(k3, selection.promptId)
        }.apply()
    }

    private fun keys(mode: AiCallMode): Triple<String, String, String> {
        val prefix = if (mode == AiCallMode.Voice) "voice" else "video"
        return Triple("${prefix}_profile_code", "${prefix}_voice_id", "${prefix}_prompt_id")
    }

    private fun android.content.SharedPreferences.Editor.putOrRemove(
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    companion object {
        // Prefixed to avoid colliding with any host SharedPreferences file.
        private const val FILE_NAME = "we_meet_ai_call_prefs"
    }
}
