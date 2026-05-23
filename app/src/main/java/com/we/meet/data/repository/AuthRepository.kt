package com.we.meet.data.repository

import com.we.meet.BuildConfig
import com.we.meet.data.api.AuthApi
import com.we.meet.data.api.dto.SendOtpRequest
import com.we.meet.data.api.dto.VerifyOtpRequest
import com.we.meet.data.auth.TokenStore
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
 * The send-otp / verify-otp endpoints do not require authentication; the
 * AuthInterceptor only attaches a Bearer header when one is already present
 * in the TokenStore, so callers don't need to opt out explicitly.
 */
class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
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
        // Best-effort: pre-fetch the user's nickname so displayUsername uses it
        // right away instead of falling back to the phone number. Failure here
        // must not block login — the phone number fallback still works.
        runCatching { fetchNickname() }
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
