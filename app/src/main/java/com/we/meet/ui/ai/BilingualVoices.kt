package com.we.meet.ui.ai

import com.we.meet.data.api.TranslationVoiceConfig
import androidx.annotation.StringRes
import com.we.meet.R

/**
 * Offline fallback only; a valid backend catalog takes precedence, including an empty one.
 * Each fallback voice was accepted by qwen3.8-livetranslate on 2026-10-09.
 * Source: https://help.aliyun.com/zh/model-studio/omni-voice-list (LiveTranslate section).
 */
internal object BilingualVoices {
    const val DEFAULT = "Tina"
    val labels = linkedMapOf(
        "Tina" to R.string.bilingual_voice_tina,
        "Cindy" to R.string.bilingual_voice_cindy,
        "Liora Mira" to R.string.bilingual_voice_liora_mira,
        "Sunnybobi" to R.string.bilingual_voice_sunnybobi,
        "Raymond" to R.string.bilingual_voice_raymond,
        "Ethan" to R.string.bilingual_voice_ethan,
        "Theo Calm" to R.string.bilingual_voice_theo_calm,
        "Serena" to R.string.bilingual_voice_serena,
        "Harvey" to R.string.bilingual_voice_harvey,
        "Maia" to R.string.bilingual_voice_maia,
        "Evan" to R.string.bilingual_voice_evan,
        "Qiao" to R.string.bilingual_voice_qiao,
        "Momo" to R.string.bilingual_voice_momo,
        "Wil" to R.string.bilingual_voice_wil,
        "Angel" to R.string.bilingual_voice_angel,
        "Li Cassian" to R.string.bilingual_voice_li_cassian,
        "Mia" to R.string.bilingual_voice_mia,
        "Joyner" to R.string.bilingual_voice_joyner,
        "Gold" to R.string.bilingual_voice_gold,
        "Katerina" to R.string.bilingual_voice_katerina,
        "Ryan" to R.string.bilingual_voice_ryan,
        "Jennifer" to R.string.bilingual_voice_jennifer,
        "Aiden" to R.string.bilingual_voice_aiden,
        "Mione" to R.string.bilingual_voice_mione,
        "Sohee" to R.string.bilingual_voice_sohee,
        "Lenn" to R.string.bilingual_voice_lenn,
        "Ono Anna" to R.string.bilingual_voice_ono_anna,
        "Sonrisa" to R.string.bilingual_voice_sonrisa,
        "Bodega" to R.string.bilingual_voice_bodega,
        "Emilien" to R.string.bilingual_voice_emilien,
        "Andre" to R.string.bilingual_voice_andre,
        "Radio Gol" to R.string.bilingual_voice_radio_gol,
        "Alek" to R.string.bilingual_voice_alek,
        "Rizky" to R.string.bilingual_voice_rizky,
        "Roya" to R.string.bilingual_voice_roya,
        "Arda" to R.string.bilingual_voice_arda,
        "Hana" to R.string.bilingual_voice_hana,
        "Dolce" to R.string.bilingual_voice_dolce,
        "Jakub" to R.string.bilingual_voice_jakub,
        "Griet" to R.string.bilingual_voice_griet,
        "Eliška" to R.string.bilingual_voice_eliska,
        "Marina" to R.string.bilingual_voice_marina,
        "Siiri" to R.string.bilingual_voice_siiri,
        "Ingrid" to R.string.bilingual_voice_ingrid,
        "Sigga" to R.string.bilingual_voice_sigga,
        "Bea" to R.string.bilingual_voice_bea,
        "Chloe" to R.string.bilingual_voice_chloe,
    )

    fun valid(config: TranslationVoiceConfig): Boolean =
        config.model == "qwen3.8-livetranslate-flash-realtime" && config.voices.size <= 500 &&
            config.voices.all { it.value.isNotBlank() && it.value.length <= 128 && it.label.isNotBlank() && it.label.length <= 128 } &&
            config.voices.map { it.value }.distinct().size == config.voices.size &&
            (if (config.voices.isEmpty()) config.defaultVoice == null else config.voices.any { it.value == config.defaultVoice })

    fun resolve(voice: String?, config: TranslationVoiceConfig?): String =
        if (config == null) resolve(voice)
        else voice?.takeIf { selected -> config.voices.any { it.value == selected } } ?: config.defaultVoice.orEmpty()

    @StringRes fun label(voice: String): Int = labels.getValue(resolve(voice))
    fun resolve(voice: String?): String = voice?.takeIf { it in labels } ?: DEFAULT
}
