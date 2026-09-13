package com.we.meet.data.auth

import android.util.Log
import com.we.meet.data.api.AuthApi
import com.we.meet.data.api.dto.RefreshTokenRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Refresh only the login session attached by AuthInterceptor. Never retarget old writes. */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val refresh: (AuthSnapshot) -> RefreshedTokens,
) : Authenticator {
    data class RefreshedTokens(val access: String, val refresh: String?, val idToken: String? = null) {
        override fun toString() = "RefreshedTokens(<private>)"
    }
    constructor(tokenStore: TokenStore, authApi: AuthApi, keycloakOidc: KeycloakOidc) : this(tokenStore, { source ->
        if (source.flow == TokenStore.AUTH_FLOW_WEB) {
            val tokens = keycloakOidc.refresh(requireNotNull(source.refresh))
            RefreshedTokens(tokens.accessToken, tokens.refreshToken, tokens.idToken)
        } else {
            val tokens = runBlocking { authApi.refresh(RefreshTokenRequest(refresh_token = requireNotNull(source.refresh))) }
            RefreshedTokens(tokens.access_token, tokens.refresh_token)
        }
    })

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request

        // Don't try to refresh on a 401 from the refresh endpoint itself
        // (that's `invalid_grant` — give up and let the original 401 surface).
        if (request.url.encodedPath.endsWith("/api/mobile/auth/refresh/")) {
            return null
        }

        val source = request.tag(AuthSnapshot::class.java) ?: return null
        val sentAuth = request.header("Authorization") ?: return null
        val current = tokenStore.authSnapshot()
        if (source.session != current.session || current.access == null || current.refresh == null) return null
        if (response.priorResponse != null) return null // At most one authentication retry.
        if (sentAuth != "Bearer ${current.access}") {
            return request.newBuilder().header("Authorization", "Bearer ${current.access}").build()
        }
        val newAccess: String
        try {
            val tokens = refresh(current)
            if (!tokenStore.rotate(current, tokens.access, tokens.refresh, tokens.idToken)) return null
            newAccess = tokens.access
        } catch (_: Exception) {
            Log.w(TAG, "refresh failed; surfacing 401")
            return null
        }

        return request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private companion object {
        const val TAG = "WeMeetAuth"
    }
}
