package com.we.meet.feature.assistant.aicall.rtc

import java.util.Locale

/** Qwen-ASR language hint; null means auto-detect. */
internal object AsrLanguage {
    private val supported = setOf(
        "zh", "yue", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es",
        "hi", "id", "th", "tr", "uk", "vi", "cs", "da", "fil", "fi", "is", "ms",
        "no", "pl", "sv",
    )

    fun current(locale: Locale = Locale.getDefault()): String? =
        locale.language.lowercase(Locale.ROOT).takeIf { it in supported }
}
