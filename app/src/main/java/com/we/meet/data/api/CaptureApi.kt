package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

/** Fixed paths and explicit idempotency headers. No automatic application retries. */
interface CaptureApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-audio-capabilities/")
    suspend fun audioCapabilities(): CaptureAudioCapabilitiesDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/")
    suspend fun create(@Header("Idempotency-Key") key: String, @Body request: CreateCaptureDto): CaptureOperationDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/")
    suspend fun read(@Path("capture") captureId: String): CaptureDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/commands/")
    suspend fun command(@Path("capture") captureId: String, @Header("Idempotency-Key") key: String,
        @Header("X-Capture-Lease") lease: String, @Body request: CaptureCommandDto): CaptureOperationDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/audio/")
    suspend fun receipts(@Path("capture") captureId: String, @Query("after_sequence") after: Int): CaptureReceiptsDto

    @Headers("Cache-Control: no-store")
    @Multipart
    @POST("api/v1.0/capture-sessions/{capture}/audio/upload/")
    suspend fun upload(@Path("capture") captureId: String, @Header("X-Capture-Lease") lease: String,
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>, @Part audio: MultipartBody.Part): CaptureAudioReceiptDto

    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/audio/seal/")
    suspend fun seal(@Path("capture") captureId: String, @Header("X-Capture-Lease") lease: String,
        @Body request: SealCaptureDto): CaptureManifestDto
}
