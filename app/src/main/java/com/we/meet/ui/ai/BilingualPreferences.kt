package com.we.meet.ui.ai

import android.content.Context
import com.we.meet.data.api.TranslationVoiceConfig
import com.we.meet.data.api.TranslationVoice
import org.json.JSONObject
import org.json.JSONArray
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.feature.assistant.scenes.TranslationScene
import java.security.MessageDigest

/** Only settings are persisted; this does not store conversation text or audio. */
internal class BilingualPreferences(context: Context, account: String) {
    private val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val prefs = context.getSharedPreferences("bilingual-settings-$key", Context.MODE_PRIVATE)

    init {
        // A one-time removal of obsolete transport keys preserves all product choices.
        if (!prefs.getBoolean("websocket_v2", false)) {
            prefs.edit().remove("direct-aoq").remove("aoq_default_v1").putBoolean("websocket_v2", true).apply()
        }
    }

    fun load(): BilingualState {
        val pair = AssistantTranslationPair(prefs.getString("source", "zh") ?: "zh", prefs.getString("target", "en") ?: "en")
            .takeIf(BilingualLanguages::valid) ?: AssistantTranslationPair()
        val sound = prefs.getBoolean("sound", true)
        val scene = TranslationScene.find(prefs.getString("scene", null))
        val fixed = prefs.getString("fixed-source", null)?.takeIf { it in setOf(pair.source, pair.target) }
        // Preserve legacy reverse directions by placing the chosen source on the left.
        val orderedPair = if (fixed == pair.target) AssistantTranslationPair(pair.target, pair.source) else pair
        val catalog = loadVoiceConfig()
        return BilingualState(pair = orderedPair, sound = sound, facing = prefs.getBoolean("facing", true),
            sceneId = scene?.id, fixedSource = fixed,
            voice = BilingualVoices.resolve(prefs.getString("voice", null), catalog), voiceConfig = catalog)
    }

    private fun loadVoiceConfig(): TranslationVoiceConfig? = runCatching {
        val json = JSONObject(prefs.getString("voice-config", null) ?: return null)
        val voices = json.getJSONArray("voices")
        TranslationVoiceConfig(json.getString("model"),
            if (json.isNull("default_voice")) null else json.getString("default_voice"),
            List(voices.length()) { index -> voices.getJSONObject(index).let { TranslationVoice(it.getString("value"), it.getString("label")) } },
            protocolVersion = json.optInt("protocol_version", 1),
            enabled = json.optBoolean("enabled", true),
            reason = if (json.isNull("reason")) null else json.optString("reason"),
            languages = json.optJSONArray("languages")?.let { values -> List(values.length()) { values.getString(it) } } ?: emptyList())
            .takeIf(BilingualVoices::valid)
    }.getOrNull()

    fun cacheVoiceConfig(config: TranslationVoiceConfig) {
        require(BilingualVoices.valid(config))
        val voices = JSONArray()
        config.voices.forEach { voices.put(JSONObject().put("value", it.value).put("label", it.label)) }
        prefs.edit().putString("voice-config", JSONObject().put("model", config.model)
            .put("default_voice", config.defaultVoice ?: JSONObject.NULL).put("voices", voices)
            .put("protocol_version", config.protocolVersion).put("enabled", config.enabled)
            .put("reason", config.reason ?: JSONObject.NULL).put("languages", JSONArray(config.languages)).toString()).apply()
    }

    fun save(state: BilingualState) {
        prefs.edit().putString("source", state.pair.source).putString("target", state.pair.target)
            .remove("aoq_default_v1").remove("direct-aoq")
            .putBoolean("facing", state.facing)
            .putString("fixed-source", state.fixedSource)
            .putString("voice", BilingualVoices.resolve(state.voice, state.voiceConfig))
            .putBoolean("sound", state.sound).putString("scene", state.sceneId).apply()
    }
}
