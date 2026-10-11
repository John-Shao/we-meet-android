package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import retrofit2.http.*

/** No registration, ASR, scores, templates or implicit directory expansion. */
interface SpeakerIdentificationApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/config/")
    suspend fun config(@Tag login: PrivateLogin): SpeakerIdentityConfigDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/speaker-identification-options/")
    suspend fun options(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Query("expected_revision") revision: Int, @Query("offset") offset: Int): IdentityOptionsDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/speaker-identification-candidates/")
    suspend fun candidates(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Query("organization_id") organization: String, @Query("expected_revision") revision: Int, @Query("q") query: String, @Query("offset") offset: Int): IdentityCandidatesDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/speaker-identification/")
    suspend fun read(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Query("request_key") key: String?): IdentityResponseDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/speaker-identification/")
    suspend fun submit(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Body body: IdentitySubmissionDto): IdentityResponseDto

    @Headers("Cache-Control: no-store")
    @HTTP(method = "DELETE", path = "api/v1.0/meeting-records/{record}/speaker-identification/", hasBody = true)
    suspend fun cancel(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Body body: IdentityCancellationDto): IdentityResponseDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/speakers/{speaker}/identity-decision/")
    suspend fun decide(@Path("record") record: String, @Path("speaker") speaker: String,
        @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: IdentitySuggestionDecisionDto): RecordSpeakerDto
}
