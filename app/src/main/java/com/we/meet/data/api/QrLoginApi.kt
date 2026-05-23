package com.we.meet.data.api

import com.we.meet.data.api.dto.QrScanResponse
import com.we.meet.data.api.dto.QrTokenRequest
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Web-QR login endpoints — bearer-only.
 *
 * Flow contract (see ../we-meet/src/backend/core/api/qr_login.py):
 *   scan    — record that the app's user has scanned this QR; echoes back
 *             the user info the web will show as "confirm sign-in as ..."
 *   confirm — mint a fresh access/refresh token pair for the web side via
 *             Keycloak Token Exchange and park it in the QR cache entry
 *   cancel  — release the QR cache entry without confirming
 */
interface QrLoginApi {

    @POST("api/qr-login/scan/")
    suspend fun scan(@Body body: QrTokenRequest): QrScanResponse

    @POST("api/qr-login/confirm/")
    suspend fun confirm(@Body body: QrTokenRequest)

    @POST("api/qr-login/cancel/")
    suspend fun cancel(@Body body: QrTokenRequest)
}
