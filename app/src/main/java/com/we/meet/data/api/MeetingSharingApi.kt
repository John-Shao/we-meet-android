package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import retrofit2.http.*

interface MeetingSharingApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/collaboration/{scope}/")
    suspend fun materialAccess(@Path("record") record: String, @Path("scope") scope: String): MaterialAccessDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/collaboration/{scope}/candidates/")
    suspend fun materialCandidates(@Path("record") record: String, @Path("scope") scope: String,
        @Query("q") query: String, @Query("offset") offset: Int, @Query("kind") kind: String): MaterialCandidatesDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/collaboration/{scope}/")
    suspend fun materialChange(@Path("record") record: String, @Path("scope") scope: String,
        @Header("Idempotency-Key") key: String, @Body body: MaterialChangeDto): MaterialReceiptDto
    @POST("api/v1.0/meeting-records/{record}/collaboration/{scope}/notifications/retry/")
    suspend fun retryMaterialNotices(@Path("record") record: String, @Path("scope") scope: String)
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-sharing/")
    suspend fun access(@Path("record") record: String, @Query("cursor") cursor: String?): SummaryShareAccessPageDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-sharing/candidates/")
    suspend fun candidates(@Path("record") record: String, @Query("scope") scope: String,
        @Query("q") query: String, @Query("cursor") cursor: String?): RecordPageDto<SummarySharePersonDto>
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-sharing/preview/")
    suspend fun preview(@Path("record") record: String, @Body body: SummaryShareSelectionDto): SummarySharePreviewDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-sharing/")
    suspend fun apply(@Path("record") record: String, @Header("Idempotency-Key") key: String, @Body body: SummaryShareRequestDto): SummaryShareReceiptDto
}
