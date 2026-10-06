package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Headers
import retrofit2.http.POST

interface AssistantTranscriptionApi {
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-transcription/session/")
    suspend fun session(): DirectAsrCredentials
}

@JsonClass(generateAdapter = true)
data class DirectAsrCredentials(
    val model: String, val url: String, val token: String,
    @Json(name = "expires_at") val expiresAt: Long,
) {
    override fun toString() = "DirectAsrCredentials(<private>)"
}
