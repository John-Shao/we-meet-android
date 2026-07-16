package com.we.meet.data.auth

import android.util.Base64
import android.util.Log
import com.we.meet.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Keycloak OIDC authorization-code + PKCE client for the in-WebView login
 * (p3-docs-app.md D2/D4).
 *
 * `app` is a PUBLIC client (no secret): the code exchange is protected by PKCE
 * instead, so this class can call the realm token endpoint directly — the
 * backend's /api/mobile/auth/ endpoints stay untouched (legacy flow only).
 *
 * Owns a plain OkHttpClient with NO AuthInterceptor and NO authenticator, for
 * the same isolation reason as ApiClient.refreshOkHttp: this class is called
 * from inside TokenRefreshAuthenticator, and routing through the main client
 * would re-attach the stale bearer and deadlock the dispatcher.
 */
class KeycloakOidc {

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String?,
    )

    /** Claims we surface from the id_token payload (display identity only). */
    data class IdClaims(val phone: String?, val nickname: String?)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---- PKCE ------------------------------------------------------------

    /** 64 random bytes, base64url — the code_verifier kept on-device. */
    fun newVerifier(): String = randomUrlSafe(64)

    /** CSRF token round-tripped through the authorize redirect. */
    fun newState(): String = randomUrlSafe(32)

    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, B64_FLAGS)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, B64_FLAGS)
    }

    // ---- Endpoints ---------------------------------------------------------

    /**
     * The unified-browser login page URL (phone-OTP + QR columns, same page the
     * web uses). `offline_access` makes the refresh token an offline token so
     * the app session outlives the realm SSO idle/max limits (D5).
     */
    fun authorizeUrl(state: String, codeChallenge: String): String =
        "$AUTH_ENDPOINT?client_id=$CLIENT_ID" +
            "&response_type=code" +
            "&scope=openid%20offline_access" +
            "&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}" +
            "&state=$state" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=S256"

    /** Trade the authorization code for tokens. Blocking — call off-main. */
    fun exchangeCode(code: String, verifier: String): Tokens = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
    )

    /**
     * Refresh the (offline) token pair. Blocking — TokenRefreshAuthenticator
     * already runs on an OkHttp worker thread. Keycloak rotates the refresh
     * token; callers MUST overwrite the stored one.
     */
    fun refresh(refreshToken: String): Tokens = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build()
    )

    private fun tokenRequest(body: FormBody): Tokens {
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("token endpoint ${response.code}: ${text.take(200)}")
            }
            val json = JSONObject(text)
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                idToken = json.optString("id_token").takeIf { it.isNotBlank() },
            )
        }
    }

    // ---- id_token claims ---------------------------------------------------

    /**
     * Best-effort parse of the id_token JWT payload for display identity.
     * Mobile users are provisioned with username = phone number, so
     * `preferred_username` is the phone; `given_name` is the nickname the
     * Account API calls firstName. No signature check — the token came to us
     * over TLS from the token endpoint we called; it is not used for authz.
     */
    fun parseIdClaims(idToken: String?): IdClaims {
        if (idToken.isNullOrBlank()) return IdClaims(null, null)
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return IdClaims(null, null)
            val json = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE), Charsets.UTF_8))
            IdClaims(
                phone = json.optString("preferred_username").takeIf { it.isNotBlank() }
                    ?: json.optString("phone_number").takeIf { it.isNotBlank() },
                nickname = json.optString("given_name").takeIf { it.isNotBlank() }
                    ?: json.optString("name").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.w(TAG, "id_token parse failed", e)
            IdClaims(null, null)
        }
    }

    // ---- Logout --------------------------------------------------------------

    /**
     * Fire-and-forget realm logout (kills the KC server-side session so the
     * next login page can't silently re-issue a code for the signed-out user).
     * Cookie clearing is the caller's job — that's a WebView/CookieManager
     * concern (AuthRepository.signOut).
     */
    fun endSessionQuietly(idToken: String?) {
        Thread {
            try {
                val url = LOGOUT_ENDPOINT +
                    "?client_id=$CLIENT_ID" +
                    (idToken?.let { "&id_token_hint=$it" } ?: "")
                http.newCall(Request.Builder().url(url).get().build()).execute().use { }
            } catch (e: Exception) {
                Log.w(TAG, "end_session failed (ignored)", e)
            }
        }.start()
    }

    companion object {
        const val REDIRECT_URI = "com.we.meet://oidc/callback"

        private val CLIENT_ID = BuildConfig.WE_MEET_OIDC_CLIENT_ID
        private val REALM_BASE =
            "${BuildConfig.WE_MEET_KEYCLOAK_URL.trimEnd('/')}/realms/meet/protocol/openid-connect"
        private val AUTH_ENDPOINT = "$REALM_BASE/auth"
        private val TOKEN_ENDPOINT = "$REALM_BASE/token"
        private val LOGOUT_ENDPOINT = "$REALM_BASE/logout"

        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private const val TAG = "WeMeetAuth"
    }
}
