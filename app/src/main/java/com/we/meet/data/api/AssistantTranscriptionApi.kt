package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Path
import com.we.meet.feature.assistant.aicall.data.DirectAILeaseInfo
import com.we.meet.feature.assistant.aicall.data.DirectAILeaseOperation

interface AssistantTranscriptionApi {
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-transcription/session/")
    suspend fun session(): DirectAsrCredentials
    @POST("api/v1.0/direct-ai/sessions/{id}/")
    suspend fun sessionLease(@Path("id") id: String, @Body operation: DirectAILeaseOperation) = Unit
}

@JsonClass(generateAdapter = true)
data class DirectAsrCredentials(
    val model: String, val url: String, val token: String,
    @Json(name = "expires_at") val expiresAt: Long,
    @Json(name = "session_lease") val sessionLease: DirectAILeaseInfo? = null,
) {
    override fun toString() = "DirectAsrCredentials(<private>)"
}
