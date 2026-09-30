package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

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
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-translation/ticket/")
    suspend fun ticket(@Body pair: AssistantTranslationPair): AssistantTranslationTicket
}
