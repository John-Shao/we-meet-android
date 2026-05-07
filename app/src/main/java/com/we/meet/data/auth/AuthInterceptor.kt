package com.we.meet.data.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <access_token>` to every outbound request,
 * unless the request opts out via the `No-Auth` header.
 *
 * The mobile auth endpoints (send-otp / verify-otp) opt out because they
 * are called before any token exists.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        if (original.header(NO_AUTH) != null) {
            // Strip the marker header before sending.
            return chain.proceed(
                original.newBuilder().removeHeader(NO_AUTH).build()
            )
        }

        val token = tokenStore.accessToken
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    companion object {
        const val NO_AUTH = "No-Auth"
    }
}
