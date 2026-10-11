package com.we.meet.data.api

import com.squareup.moshi.Json
import com.we.meet.data.api.dto.IdentityPersonDto
import com.we.meet.data.api.dto.VoiceprintPageDto
import com.we.meet.data.api.dto.VoiceprintScopeDto
import com.we.meet.data.auth.PrivateLogin
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class RecordingImportIdentity(
    @Json(name = "organization_id") val organizationId: String?,
    @Json(name = "candidate_user_ids") val candidateUserIds: List<String>,
) { override fun toString() = "RecordingImportIdentity(<private>)" }

data class RecordingIdentityCapability(
    val available: Boolean, val reason: String = "",
    @Json(name = "max_candidates") val maxCandidates: Int = 0,
    @Json(name = "max_bytes") val maxBytes: Long = 0,
    @Json(name = "max_duration_ms") val maxDurationMs: Long = 0,
)
data class RecordingIdentityPreflight(
    val status: String, val reason: String = "",
    @Json(name = "can_continue_without_identity") val canContinueWithoutIdentity: Boolean = false,
)
data class RecordingIdentityRequest(val status: String, val reason: String = "")
data class RecordingImportCandidates(
    @Json(name = "organization_id") val organizationId: String?,
    val results: List<IdentityPersonDto>,
    @Json(name = "next_offset") val nextOffset: Int?,
)
data class RecordingPreflightDecision(@Json(name = "expected_attempt") val expectedAttempt: Int, val action: String)

/** Same upload contracts with a local login fence, never carried to object storage. */
interface RecordingImportApi {
    @Headers("Cache-Control: no-store") @GET("api/v1.0/recording-uploads/")
    suspend fun capabilities(@Tag login: PrivateLogin): RecordingUploadCapabilities
    @Headers("Cache-Control: no-store") @GET("api/v1.0/recording-hotwords/")
    suspend fun personalHotwords(@Tag login: PrivateLogin): PersonalHotwordsDto
    @Headers("Cache-Control: no-store") @PUT("api/v1.0/recording-hotwords/")
    suspend fun savePersonalHotwords(@Tag login: PrivateLogin, @Body body: PersonalHotwordsRequest): PersonalHotwordsDto
    @Multipart @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/")
    suspend fun upload(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Part("key") key: RequestBody, @Part audio: MultipartBody.Part, @Part("context") context: RequestBody,
        @Part("hotwords") hotwords: RequestBody, @Part("diarization") diarization: RequestBody,
        @Part("identity") identity: RequestBody?): RecordingUploadState
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/upload-url/")
    suspend fun presign(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingUploadPresign): RecordingUploadTicket
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/upload-complete/")
    suspend fun complete(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingUploadComplete): RecordingUploadState
    @Headers("Cache-Control: no-store") @GET("api/v1.0/recording-uploads/{record}/")
    suspend fun state(@Path("record") record: String, @Tag login: PrivateLogin): RecordingUploadState
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/{record}/")
    suspend fun retry(@Path("record") record: String, @Tag login: PrivateLogin, @Body body: RecordingUploadRetry): RecordingUploadState
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/multipart/begin/")
    suspend fun multipartBegin(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingUploadBegin): RecordingUploadPlan
    @Headers("Cache-Control: no-store") @GET("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartResume(@Path("session") session: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin): RecordingUploadPlan
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/multipart/{session}/parts/")
    suspend fun multipartSign(@Path("session") session: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingUploadSign): RecordingUploadPlan
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartComplete(@Path("session") session: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingUploadFinish): RecordingUploadState
    @Headers("Cache-Control: no-store") @DELETE("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartAbort(@Path("session") session: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin)
    @Headers("Cache-Control: no-store") @GET("api/v1.0/voiceprint/scopes/")
    suspend fun scopes(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Query("offset") offset: Int): VoiceprintPageDto<VoiceprintScopeDto>
    @Headers("Cache-Control: no-store") @GET("api/v1.0/recording-uploads/identity-candidates/")
    suspend fun candidates(@Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin,
        @Query("organization_id") organization: String, @Query("q") query: String, @Query("offset") offset: Int): RecordingImportCandidates
    @Headers("Cache-Control: no-store") @POST("api/v1.0/recording-uploads/{record}/identity-preflight/")
    suspend fun decide(@Path("record") record: String, @Header("X-Voiceprint-Owner") owner: String, @Tag login: PrivateLogin, @Body body: RecordingPreflightDecision): RecordingUploadState
}
