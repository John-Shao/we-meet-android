package com.we.meet.data.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Catches HTTP 401 on any authenticated request, clears the stored
 * credentials, and broadcasts a session-expired signal via [SessionState]
 * so the UI layer can prompt the user to sign in again.
 *
 * Only authenticated requests trigger the signal: presence of the
 * `Authorization` header is the marker (set upstream by
 * [AuthInterceptor]). A 401 on an anonymous call — e.g. `send-otp` with a
 * wrong phone — means "bad input", not "session expired", and is left for
 * the caller to handle via the usual error translation path.
 */
class SessionExpiredInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 401 && request.header("Authorization") != null) {
            tokenStore.clear()
            SessionState.markExpired()
        }
        return response
    }
}
