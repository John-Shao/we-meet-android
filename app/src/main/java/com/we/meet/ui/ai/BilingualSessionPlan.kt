package com.we.meet.ui.ai

import com.we.meet.data.api.AssistantTranslationPair

/** Explicit direction needs one translator; automatic routing also needs a detector. */
internal data class BilingualSessionPlan(val pair: AssistantTranslationPair, val source: String? = null) {
    init { require(pair.source != pair.target && (source == null || source == pair.source || source == pair.target)) }
    val automatic get() = source == null
    val connections get() = if (automatic) 3 else 1
    val inputLanguage get() = source ?: pair.source
    val outputLanguage get() = if (inputLanguage == pair.source) pair.target else pair.source
}
