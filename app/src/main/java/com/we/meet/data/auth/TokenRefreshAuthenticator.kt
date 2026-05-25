package com.we.meet.data.auth

import android.util.Log
import com.we.meet.data.api.AuthApi
import com.we.meet.data.api.dto.RefreshTokenRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] that silently refreshes the bearer on a 401.
 *
 * Flow:
 *   1. A request comes back with HTTP 401.
 *   2. Bail out for refresh-itself / no-stored-creds cases.
 *   3. If another thread already rotated the access token while we were
 *      blocked here, just retry with the new token — no extra refresh round
 *      trip needed (this is the single-flight optimisation; [@Synchronized]
 *      serialises us against parallel 401s).
 *   4. Otherwise call /api/mobile/auth/refresh/, persist the new
 *      access+refresh pair in [TokenStore], and return the original request
 *      rebuilt with the new bearer so OkHttp transparently retries it.
 *   5. If refresh itself fails (network blip, invalid_grant), return null —
 *      the original 401 propagates to [SessionExpiredInterceptor], which
 *      then clears the tokens and pushes the user back to the login screen.
 *
 * Note: [authApi] MUST be backed by an OkHttpClient that does NOT itself
 * install this authenticator nor [AuthInterceptor]. Routing the refresh
 * through the same client deadlocks: the [runBlocking] call inside the
 * authenticator blocks an OkHttp worker thread and re-enqueues onto the
 * same dispatcher, while [AuthInterceptor] would re-attach the very
 * bearer that triggered the 401 in the first place.
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) : Authenticator {

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request

        // Don't try to refresh on a 401 from the refresh endpoint itself
        // (that's `invalid_grant` — give up and let the original 401 surface).
        if (request.url.encodedPath.endsWith("/api/mobile/auth/refresh/")) {
            return null
        }

        // Bail if we never had bearer creds (cookie-only flows hit this with
        // no Authorization header — leave them for the caller to handle).
        val sentAuth = request.header("Authorization") ?: return null
        val storedAccess = tokenStore.accessToken ?: return null
        val storedRefresh = tokenStore.refreshToken ?: return null

        // Concurrency: another thread refreshed between our 401 and our turn
        // in the synchronized block. Skip the refresh round trip and just
        // retry with the new bearer.
        if (sentAuth != "Bearer $storedAccess") {
            return request.newBuilder()
                .header("Authorization", "Bearer $storedAccess")
                .build()
        }

        val refreshed = try {
            runBlocking {
                authApi.refresh(RefreshTokenRequest(refresh_token = storedRefresh))
            }
        } catch (e: Exception) {
            // Backend returned 4xx/5xx, or network failure. Bail; the 401
            // propagates so the user is sent back to login.
            Log.w(TAG, "refresh failed; surfacing 401", e)
            return null
        }

        tokenStore.accessToken = refreshed.access_token
        tokenStore.refreshToken = refreshed.refresh_token

        return request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.access_token}")
            .build()
    }

    private companion object {
        const val TAG = "WeMeetAuth"
    }
}
