package com.we.meet.ui.ai

import androidx.annotation.StringRes
import com.we.meet.R
import com.we.meet.data.api.AssistantTranslationPair

/** LiveTranslate 3.8 languages supporting both audio and text output. */
internal object BilingualLanguages {
    val labels: Map<String, Int> = linkedMapOf(
        "zh" to R.string.bilingual_language_zh, "en" to R.string.bilingual_language_en,
        "ar" to R.string.bilingual_language_ar, "de" to R.string.bilingual_language_de,
        "fr" to R.string.bilingual_language_fr, "es" to R.string.bilingual_language_es,
        "pt" to R.string.bilingual_language_pt, "id" to R.string.bilingual_language_id,
        "it" to R.string.bilingual_language_it, "ko" to R.string.bilingual_language_ko,
        "ru" to R.string.bilingual_language_ru, "th" to R.string.bilingual_language_th,
        "vi" to R.string.bilingual_language_vi, "ja" to R.string.bilingual_language_ja,
        "tr" to R.string.bilingual_language_tr, "hi" to R.string.bilingual_language_hi,
        "ms" to R.string.bilingual_language_ms, "nl" to R.string.bilingual_language_nl,
        "ur" to R.string.bilingual_language_ur, "nb" to R.string.bilingual_language_nb,
        "sv" to R.string.bilingual_language_sv, "da" to R.string.bilingual_language_da,
        "he" to R.string.bilingual_language_he, "fi" to R.string.bilingual_language_fi,
        "pl" to R.string.bilingual_language_pl, "is" to R.string.bilingual_language_is,
        "cs" to R.string.bilingual_language_cs, "fil" to R.string.bilingual_language_fil,
        "fa" to R.string.bilingual_language_fa,
    )

    @StringRes fun label(code: String): Int = labels.getValue(code)

    fun valid(pair: AssistantTranslationPair): Boolean =
        pair.source != pair.target && pair.source in labels && pair.target in labels

    /** Choosing the other side swaps the pair, so both sides always stay distinct. */
    fun select(pair: AssistantTranslationPair, first: Boolean, language: String): AssistantTranslationPair {
        require(valid(pair) && language in labels)
        return if (first) AssistantTranslationPair(language, if (language == pair.target) pair.source else pair.target)
        else AssistantTranslationPair(if (language == pair.source) pair.target else pair.source, language)
    }

    fun opposite(pair: AssistantTranslationPair, source: String): String {
        require(valid(pair) && source in setOf(pair.source, pair.target))
        return if (source == pair.source) pair.target else pair.source
    }
}
