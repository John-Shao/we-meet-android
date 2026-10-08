package com.we.meet.ui.ai

import android.content.Context
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.feature.assistant.scenes.TranslationScene
import java.security.MessageDigest

/** Only settings are persisted; this does not store conversation text or audio. */
internal class BilingualPreferences(context: Context, account: String) {
    private val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val prefs = context.getSharedPreferences("bilingual-settings-$key", Context.MODE_PRIVATE)

    fun load(): BilingualState {
        // Migrate the former cloud default once without overwriting later user choices.
        if (!prefs.getBoolean("aoq_default_v1", false)) {
            prefs.edit().putBoolean("direct-aoq", true).putBoolean("aoq_default_v1", true).apply()
        }
        val pair = AssistantTranslationPair(prefs.getString("source", "zh") ?: "zh", prefs.getString("target", "en") ?: "en")
            .takeIf(BilingualLanguages::valid) ?: AssistantTranslationPair()
        val sound = prefs.getBoolean("sound", true)
        val scene = TranslationScene.find(prefs.getString("scene", null))
        val direct = prefs.getBoolean("direct-aoq", true)
        val fixed = prefs.getString("fixed-source", null)?.takeIf { direct && it in setOf(pair.source, pair.target) }
        return BilingualState(pair = pair, sound = sound, sceneId = scene?.id, directAoq = direct, fixedSource = fixed)
    }

    fun save(state: BilingualState) {
        prefs.edit().putString("source", state.pair.source).putString("target", state.pair.target)
            .putBoolean("aoq_default_v1", true)
            .putString("fixed-source", state.fixedSource)
            .putBoolean("direct-aoq", state.directAoq).putBoolean("sound", state.sound).putString("scene", state.sceneId).apply()
    }
}
