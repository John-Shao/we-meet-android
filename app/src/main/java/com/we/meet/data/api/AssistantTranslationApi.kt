package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import com.we.meet.feature.assistant.aicall.data.DirectAILeaseInfo
import com.we.meet.feature.assistant.aicall.data.DirectAILeaseOperation

@JsonClass(generateAdapter = true)
data class AssistantTranslationPair(
    @Json(name = "source_language") val source: String = "zh",
    @Json(name = "target_language") val target: String = "en",
)

@JsonClass(generateAdapter = true)
data class AssistantTranslationTicket(val url: String, val ticket: String) {
    override fun toString() = "AssistantTranslationTicket(<private>)"
}

interface AssistantTranslationApi {
    @POST("api/v1.0/direct-ai/sessions/{id}/")
    suspend fun sessionLease(@Path("id") id: String, @Body operation: DirectAILeaseOperation) = Unit
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-translation/session/")
    suspend fun directSession(@Body request: AssistantTranslationDirectRequest): AssistantTranslationDirectSession =
        error("Direct translation is unavailable")
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-translation/ticket/")
    suspend fun ticket(@Body pair: AssistantTranslationPair): AssistantTranslationTicket
}

@JsonClass(generateAdapter = true)
data class AssistantTranslationDirectRequest(
    @Json(name = "source_language") val source: String,
    @Json(name = "target_language") val target: String,
    val purpose: String = "translation",
)

@JsonClass(generateAdapter = true)
data class AssistantTranslationDirectSession(
    val model: String,
    val aoq: com.we.meet.feature.assistant.aicall.model.AoqCredentials,
    @Json(name = "session_lease") val sessionLease: DirectAILeaseInfo? = null,
) {
    override fun toString() = "AssistantTranslationDirectSession(<private>)"
}
