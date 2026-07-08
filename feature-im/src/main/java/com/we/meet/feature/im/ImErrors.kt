package com.we.meet.feature.im

import androidx.annotation.StringRes
import com.jusi.lightim.AuthException
import com.jusi.lightim.NetworkException
import com.jusi.lightim.ProtocolException
import com.jusi.lightim.TimeoutException

/**
 * Maps a failure to a localized, user-facing message resource.
 *
 * Error channels carry `@StringRes Int?` rather than a raw `String?` on
 * purpose: every [com.jusi.lightim.ImException] message is a *developer*
 * string — `"transport failed"`, `"500 Internal Server Error"`,
 * `"404 Not Found: {json body}"`, `"response body not valid JSON"`. None of
 * them are meant for a user, so discriminating on type loses no information
 * that a user could act on, and it keeps technical text off the screen.
 *
 * Diagnostics are not lost: call sites still `Log.w(TAG, "…", e)` with the
 * original throwable before storing the resource id.
 */
@StringRes
fun Throwable.userMessageRes(): Int = when (this) {
    is NetworkException -> R.string.im_error_network
    is TimeoutException -> R.string.im_error_timeout
    is AuthException -> R.string.im_error_auth
    // ProtocolException means a schema/4xx mismatch the user can't act on;
    // it deliberately falls through to the generic message.
    is ProtocolException -> R.string.im_error_unknown
    else -> R.string.im_error_unknown
}
