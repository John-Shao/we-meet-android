package com.we.meet.data.api.dto

import com.squareup.moshi.JsonClass

/**
 * DTOs for the web → app QR-login dance. The app calls scan/confirm/cancel
 * after the user scans a QR shown on the web; the web is the one polling
 * /poll/, so the app never needs to read those responses.
 *
 * Backend: src/backend/core/api/qr_login.py.
 */

@JsonClass(generateAdapter = true)
data class QrTokenRequest(
    val token: String,
)

@JsonClass(generateAdapter = true)
data class QrScanUser(
    val phone: String,
    val name: String,
)

/** Response from POST /api/qr-login/scan/ — echoes back the scanned user. */
@JsonClass(generateAdapter = true)
data class QrScanResponse(
    val user: QrScanUser,
)
