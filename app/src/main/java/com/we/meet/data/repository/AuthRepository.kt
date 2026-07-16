package com.we.meet.data.repository

import android.webkit.CookieManager
import com.we.meet.BuildConfig
import com.we.meet.data.api.AuthApi
import com.we.meet.data.api.dto.SendOtpRequest
import com.we.meet.data.api.dto.VerifyOtpRequest
import com.we.meet.data.auth.KeycloakOidc
import com.we.meet.data.auth.TokenStore
import com.we.meet.push.PushTokenUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Login / logout flow.  Wraps the mobile auth endpoints and persists the
 * resulting tokens via [TokenStore].
 *
 * Two login flows coexist (p3-docs-app.md D1/D4):
 *  - web:   in-WebView Keycloak unified login (authorization code + PKCE,
 *           public client `app`) — [completeWebLogin]. Seeds the KC session
 *           cookie in the process-wide CookieManager, which is what lets the
 *           Docs tab WebView SSO silently.
 *  - legacy: native OTP → backend token exchange — [sendOtp]/[verifyOtp].
 *           Kept as the BuildConfig.WE_MEET_WEB_LOGIN=false fallback.
 *
 * The send-otp / verify-otp endpoints do not require authentication; the
 * AuthInterceptor only attaches a Bearer header when one is already present
 * in the TokenStore, so callers don't need to opt out explicitly.
 */
class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
    private val keycloakOidc: KeycloakOidc,
) {

    /** Send a 6-digit SMS code to [phone].  Returns Result.failure on any error. */
    suspend fun sendOtp(phone: String): Result<Unit> = runCatching {
        authApi.sendOtp(SendOtpRequest(phone = phone))
        Unit
    }

    /** Verify [otp] for [phone] and persist the returned tokens. */
    suspend fun verifyOtp(phone: String, otp: String): Result<Unit> = runCatching {
        val resp = authApi.verifyOtp(VerifyOtpRequest(phone = phone, otp = otp))
        tokenStore.accessToken = resp.access_token
        tokenStore.refreshToken = resp.refresh_token
        tokenStore.phone = phone
        // Push: now that a Bearer exists, register the Getui cid (no-op when
        // the cid callback hasn't fired yet — that path retries by itself).
        com.we.meet.push.PushTokenUploader.uploadIfPossible()
        // Best-effort: pre-fetch the user's nickname so displayUsername uses it
        // right away instead of falling back to the phone number. Failure here
        // must not block login — the phone number fallback still works.
        runCatching { fetchNickname() }
    }

    /**
     * Finish the in-WebView Keycloak login: trade the authorization [code]
     * (+ PKCE [verifier]) for tokens and persist them, mirroring verifyOtp's
     * side effects (push registration, nickname prefetch). Runs off-main.
     */
    suspend fun completeWebLogin(code: String, verifier: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val tokens = keycloakOidc.exchangeCode(code, verifier)
            tokenStore.accessToken = tokens.accessToken
            tokenStore.refreshToken = tokens.refreshToken
            tokenStore.idToken = tokens.idToken
            tokenStore.authFlow = TokenStore.AUTH_FLOW_WEB
            // Display identity straight from the id_token (username = phone for
            // mobile-provisioned users); Account API prefetch stays best-effort.
            val claims = keycloakOidc.parseIdClaims(tokens.idToken)
            claims.phone?.let { tokenStore.phone = it }
            claims.nickname?.let { tokenStore.nickname = it }
            PushTokenUploader.uploadIfPossible()
            runCatching { fetchNickname() }
        }
        Unit
    }

    /** Fetch the user's nickname (firstName) from Keycloak Account API. */
    suspend fun fetchNickname(): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(KEYCLOAK_ACCOUNT_URL)
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch account: ${response.code}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response")
            val regex = """"firstName"\s*:\s*"([^"]*)"""".toRegex()
            val match = regex.find(body)
            val firstName = match?.groupValues?.get(1) ?: ""
            if (firstName.isNotBlank()) {
                tokenStore.nickname = firstName
            }
            firstName
        }
    }

    /** Update the user's nickname (firstName) via Keycloak Account API. */
    suspend fun updateNickname(nickname: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val json = """{"firstName":"$nickname"}"""
            val reqBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(KEYCLOAK_ACCOUNT_URL)
                .post(reqBody)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Failed to update nickname: ${response.code}")
            }
            tokenStore.nickname = nickname
        }
    }

    fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    fun signOut() {
        // Best-effort push-token unregister; fired before the token clear
        // (races it — a lost race is harmless, see unregisterQuietly's doc).
        PushTokenUploader.unregisterQuietly()
        if (tokenStore.isWebFlow()) {
            // Kill the KC server-side session (fire-and-forget) and wipe the
            // WebView cookies — otherwise the next login page would silently
            // re-issue a code for the account that just signed out, and the
            // Docs tab would stay logged in as them (p3-docs-app.md D8).
            keycloakOidc.endSessionQuietly(tokenStore.idToken)
            runCatching {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
            }
        }
        tokenStore.clear()
    }

    private companion object {
        // Keycloak issuer URL is set at build time via WE_MEET_KEYCLOAK_URL
        // (gradle.properties / local.properties). Must match the issuer that
        // signed the backend's access_token, otherwise Account API → 401 → the
        // SessionExpiredInterceptor trips and the user sees "session expired"
        // right after a successful login. See ApiClient for the OkHttp wiring.
        val KEYCLOAK_ACCOUNT_URL: String =
            "${BuildConfig.WE_MEET_KEYCLOAK_URL.trimEnd('/')}/realms/meet/account"
    }
}
