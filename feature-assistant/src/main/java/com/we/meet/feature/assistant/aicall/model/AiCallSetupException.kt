package com.we.meet.feature.assistant.aicall.model

import androidx.annotation.StringRes

/** Stable, localized startup failures; provider credentials never enter messages. */
class AiCallSetupException(
    @StringRes val messageRes: Int,
    val stage: String,
    cause: Throwable? = null,
) : Exception(stage, cause)
