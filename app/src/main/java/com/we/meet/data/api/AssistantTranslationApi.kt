package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class AssistantTranslationPair(
    @Json(name = "source_language") val source: String = "zh",
    @Json(name = "target_language") val target: String = "en",
)

@JsonClass(generateAdapter = true)
data class AssistantTranslationTicket(val url: String, val ticket: String,
    @Json(name = "protocol_version") val protocolVersion: Int = 1,
    @Json(name = "record_other_language") val recordOtherLanguage: Boolean = false) {
    override fun toString() = "AssistantTranslationTicket(<private>)"
}

@JsonClass(generateAdapter = true)
data class TranslationVoice(val value: String, val label: String)

@JsonClass(generateAdapter = true)
data class TranslationVoiceConfig(
    val model: String,
    @Json(name = "default_voice") val defaultVoice: String?,
    val voices: List<TranslationVoice>,
    @Json(name = "protocol_version") val protocolVersion: Int = 1,
    val enabled: Boolean = true,
    val reason: String? = null,
    val languages: List<String> = emptyList(),
    @Json(name = "fixed_transcription_languages") val fixedTranscriptionLanguages: List<String> = emptyList(),
)

interface AssistantTranslationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/assistant-translation/config/")
    suspend fun voiceConfig(): TranslationVoiceConfig = error("Voice config is unavailable")

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-translation/ticket/")
    suspend fun ticket(@Body request: AssistantTranslationRequest): AssistantTranslationTicket
}

@JsonClass(generateAdapter = true)
data class AssistantTranslationRequest(
    @Json(name = "source_language") val source: String,
    @Json(name = "target_language") val target: String,
    @Json(name = "fixed_source") val fixedSource: String? = null,
    val voice: String,
    @Json(name = "protocol_version") val protocolVersion: Int = 2,
    @Json(name = "record_other_language") val recordOtherLanguage: Boolean = fixedSource != null,
)
