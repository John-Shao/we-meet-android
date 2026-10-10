package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.PrivateLogin
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

/** Fixed first-party paths; all calls are private and bound before credentials attach. */
interface VoiceprintApi {
    @Headers("Cache-Control: no-store") @GET("api/v1.0/config/")
    suspend fun samplingConfiguration(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin): SpeakerIdentityConfigDto
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/sampling-connection/")
    suspend fun samplingConnection(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Query("room_sid") room: String, @Query("participant_sid") participant: String): VoiceprintCallConnectionDto
    @Headers("Cache-Control: no-store") @PATCH("api/v1.0/voiceprint/sampling-control/")
    suspend fun samplingDeclaration(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Body body: VoiceprintCallDeclarationDto): VoiceprintCallControlDto
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/scopes/")
    suspend fun scopes(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("offset") offset: Int): VoiceprintPageDto<VoiceprintScopeDto>
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/settings/")
    suspend fun settings(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("organization_id") organization: String?): VoiceprintSettingsDto
    @Headers("Cache-Control: no-store") @PATCH("api/v1.0/voiceprint/settings/")
    suspend fun change(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: Map<String, @JvmSuppressWildcards Any?>): VoiceprintSettingsDto
    @Headers("Cache-Control: no-store") @PATCH("api/v1.0/voiceprint/organizations/{organization}/settings/")
    suspend fun policy(@Path("organization") organization: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: VoiceprintPolicyChangeDto): VoiceprintPolicyDto
    @Headers("Cache-Control: no-store") @POST("api/v1.0/voiceprint/enrollments/")
    suspend fun begin(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: VoiceprintEnrollmentRequestDto): VoiceprintEnrollmentDto
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/enrollments/{enrollment}/")
    suspend fun enrollment(@Path("enrollment") enrollment: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin): VoiceprintEnrollmentDto
    @Headers("Cache-Control: no-store") @PUT("api/v1.0/voiceprint/enrollments/{enrollment}/clips/{slot}/")
    suspend fun upload(@Path("enrollment") enrollment: String, @Path("slot") slot: Int, @Header("X-Voiceprint-Owner") owner: String,
        @Tag login: PrivateLogin, @Header("X-Voiceprint-Upload-Token") token: String, @Body wav: RequestBody): VoiceprintSampleDto
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/samples/")
    suspend fun samples(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("organization_id") organization: String?, @Query("offset") offset: Int): VoiceprintPageDto<VoiceprintSampleDto>
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/samples/{sample}/")
    suspend fun sample(@Path("sample") sample: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("organization_id") organization: String?): VoiceprintSampleDto
    @Streaming @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/samples/{sample}/audio/")
    suspend fun audio(@Path("sample") sample: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin): ResponseBody
    @Headers("Cache-Control: no-store") @POST("api/v1.0/voiceprint/samples/{sample}/decision/")
    suspend fun decide(@Path("sample") sample: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: VoiceprintDecisionDto): VoiceprintSampleDto
    @HTTP(method = "DELETE", path = "api/v1.0/voiceprint/profiles/{profile}/", hasBody = true) @Headers("Cache-Control: no-store")
    suspend fun remove(@Path("profile") profile: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: VoiceprintRemovalDto): VoiceprintDeletionDto
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/deletions/")
    suspend fun deletions(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("organization_id") organization: String?, @Query("offset") offset: Int): VoiceprintPageDto<VoiceprintDeletionDto>
}
