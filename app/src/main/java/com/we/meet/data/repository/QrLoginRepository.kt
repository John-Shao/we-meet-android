package com.we.meet.data.repository

import com.we.meet.data.api.QrLoginApi
import com.we.meet.data.api.dto.QrScanUser
import com.we.meet.data.api.dto.QrTokenRequest

/**
 * Thin wrapper around [QrLoginApi] — every method returns a [Result] so the
 * UI layer can pattern-match on success/failure without a try/catch.
 */
class QrLoginRepository(
    private val qrLoginApi: QrLoginApi,
) {

    /** Notify the backend that this device's user scanned the QR. */
    suspend fun scan(token: String): Result<QrScanUser> = runCatching {
        qrLoginApi.scan(QrTokenRequest(token = token)).user
    }

    /** Confirm the login — backend mints a fresh token pair for the web. */
    suspend fun confirm(token: String): Result<Unit> = runCatching {
        qrLoginApi.confirm(QrTokenRequest(token = token))
    }

    /** Release the QR slot without confirming. Best-effort. */
    suspend fun cancel(token: String): Result<Unit> = runCatching {
        qrLoginApi.cancel(QrTokenRequest(token = token))
    }
}
