package com.we.meet.data.api

import com.squareup.moshi.Json
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

data class RecordingUploadCapabilities(
    val available: Boolean,
    @Json(name = "max_bytes") val maxBytes: Long = 0,
    val extensions: List<String> = emptyList(),
    /** True once the server offers presigned direct uploads. */
    @Json(name = "direct_upload_available") val directUploadAvailable: Boolean = false,
    /** The direct branch's ceiling; 0 when direct uploads are off. */
    @Json(name = "direct_max_bytes") val directMaxBytes: Long = 0,
)

data class RecordingUploadState(
    @Json(name = "record_id") val recordId: String,
    val status: String,
    val attempt: Int,
    val retryable: Boolean = false,
)

data class RecordingUploadRetry(val attempt: Int)

/** The declaration signed into the PUT, plus the options the job will carry. */
data class RecordingUploadPresign(
    val key: String,
    val name: String,
    val size: Long,
    @Json(name = "content_type") val contentType: String,
    val context: String,
    val hotwords: String,
)

/** One signed PUT, valid for one exact object. */
data class RecordingUploadTicket(
    @Json(name = "upload_url") val uploadUrl: String,
    @Json(name = "storage_name") val storageName: String,
    val headers: Map<String, String> = emptyMap(),
)

/** The same declaration plus the storage key the server handed out. */
data class RecordingUploadComplete(
    val key: String,
    val name: String,
    val size: Long,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "storage_name") val storageName: String,
    val context: String,
    val hotwords: String,
)

interface RecordingUploadApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/")
    suspend fun capabilities(): RecordingUploadCapabilities

    @Multipart
    @POST("api/v1.0/recording-uploads/")
    suspend fun upload(
        @Part("key") key: RequestBody,
        @Part audio: MultipartBody.Part,
        @Part("context") context: RequestBody,
        @Part("hotwords") hotwords: RequestBody,
    ): RecordingUploadState

    /** Step one of the direct flow: sign one PUT for an exact byte count. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/upload-url/")
    suspend fun presign(@Body body: RecordingUploadPresign): RecordingUploadTicket

    /** Step two: adopt the object after storage has it. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/upload-complete/")
    suspend fun complete(@Body body: RecordingUploadComplete): RecordingUploadState

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/{record}/")
    suspend fun state(@Path("record") recordId: String): RecordingUploadState

    @POST("api/v1.0/recording-uploads/{record}/")
    suspend fun retry(@Path("record") recordId: String, @Body body: RecordingUploadRetry): RecordingUploadState

    // --- Resumable chunked upload ------------------------------------------
    // Object storage's multipart API is the only way a broken transfer can
    // resume. The server holds the upload id and asks storage which parts
    // landed, because a client that trusted its own notes would skip a part
    // that never arrived.

    /** Open an upload, or be handed the plan for one already open. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/multipart/begin/")
    suspend fun multipartBegin(@Body body: RecordingUploadBegin): RecordingUploadPlan

    /** What storage already holds, so finished parts need not be re-sent. */
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartResume(@Path("session") sessionId: String): RecordingUploadPlan

    /** Sign a batch of part PUTs; the PUTs themselves never reach this app. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/multipart/{session}/parts/")
    suspend fun multipartSign(@Path("session") sessionId: String, @Body body: RecordingUploadSign): RecordingUploadPlan

    /** Reassemble and adopt. */
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartComplete(@Path("session") sessionId: String, @Body body: RecordingUploadFinish): RecordingUploadState

    /** Abort. Storage bills the parts of an upload left incomplete. */
    @Headers("Cache-Control: no-store")
    @DELETE("api/v1.0/recording-uploads/multipart/{session}/")
    suspend fun multipartAbort(@Path("session") sessionId: String)
}

/** The declaration signed into the upload, before any byte moves. */
data class RecordingUploadBegin(
    val key: String,
    val name: String,
    val size: Long,
    @Json(name = "content_type") val contentType: String,
    val context: String,
    val hotwords: String,
)

/** One signed part PUT. */
data class RecordingUploadPartPlan(
    @Json(name = "part_number") val partNumber: Int,
    val url: String,
    @Json(name = "expected_bytes") val expectedBytes: Long,
)

data class RecordingUploadHeldPart(
    @Json(name = "part_number") val partNumber: Int,
    val etag: String,
    val size: Long,
)

/** The plan, or the state of a resumed upload. */
data class RecordingUploadPlan(
    @Json(name = "session_id") val sessionId: String,
    val size: Long,
    @Json(name = "part_size") val partSize: Long,
    @Json(name = "part_count") val partCount: Int,
    val uploaded: List<RecordingUploadHeldPart> = emptyList(),
    @Json(name = "uploaded_bytes") val uploadedBytes: Long = 0,
    val parts: List<RecordingUploadPartPlan> = emptyList(),
)

data class RecordingUploadSign(val parts: List<Int>)

data class RecordingUploadPartTag(
    @Json(name = "part_number") val partNumber: Int,
    val etag: String,
)

data class RecordingUploadFinish(val parts: List<RecordingUploadPartTag>)
