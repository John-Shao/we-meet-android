package com.we.meet.feature.assistant.aicall.rtc

import java.util.Locale

/**
 * Qwen-ASR language hint. Only Mandarin is pinned: short Chinese utterances are
 * easily mistaken for Hindi/other languages by auto-detection. Other locales
 * keep auto-detection so an English UI user can still speak Chinese.
 */
internal object AsrLanguage {
    fun current(locale: Locale = Locale.getDefault()): String? =
        when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> "zh"
            "yue" -> "yue"
            else -> null
        }
}
