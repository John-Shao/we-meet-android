package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Error envelope returned by the backend. Mobile-specific endpoints use
 * `error` (mobile auth doc, `mobile_auth.py`); DRF defaults use `detail`
 * (e.g. `{"detail": "No Room matches the given query."}` from
 * `get_object_or_404`). Both fields are optional; the translator picks
 * whichever is present.
 */
@JsonClass(generateAdapter = true)
data class ApiErrorBody(
    val error: String? = null,
    val detail: String? = null,
)
