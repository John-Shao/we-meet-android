package com.we.meet.feature.assistant.util

import com.squareup.moshi.JsonClass

/**
 * Error envelope returned by the backend. Mobile-specific endpoints use
 * `error`; DRF defaults use `detail`. Both optional; the translator picks
 * whichever is present.
 */
@JsonClass(generateAdapter = true)
data class ApiErrorBody(
    val error: String? = null,
    val detail: String? = null,
)
