package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * Body for `POST /rooms/{idOrSlug}/request-entry/`.
 *
 * No auth required (`permission_classes=[]` on the backend); the lobby
 * service tracks participants via an HTTP cookie. The same endpoint is
 * called repeatedly for status polling — server is idempotent and
 * refreshes the waiting timeout on each call.
 */
@JsonClass(generateAdapter = true)
data class RequestEntryRequest(
    val username: String,
)

/**
 * Response for request-entry. Status transitions: `unknown` → `waiting`
 * → (`accepted` | `denied`). When `accepted`, [livekit] is populated and
 * the visitor can connect directly. `denied` and `waiting` carry no
 * livekit block.
 *
 * Note we DON'T include `id` here — the server uses an HTTP-only cookie
 * to identify the participant across polls, so the client doesn't need
 * to (and can't) read the lobby id. The reason `id` shows up in
 * waiting-participants/ is that's for the owner to admit specific
 * waiters; visitor polling doesn't need it.
 */
@JsonClass(generateAdapter = true)
data class RequestEntryResponse(
    val status: String,
    val username: String?,
    val color: String?,
    val livekit: LiveKitDto? = null,
)
