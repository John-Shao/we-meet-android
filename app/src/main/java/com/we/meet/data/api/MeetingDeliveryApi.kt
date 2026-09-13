package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import retrofit2.http.*

interface MeetingDeliveryApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/document-exports/")
    suspend fun exports(@Path("record") record: String): SummaryExportsDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/document-exports/preview/")
    suspend fun preview(@Path("record") record: String, @Query("source_kind") kind: String,
        @Query("source_id") source: String, @Query("language") language: String): SummaryExportPreviewDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/document-exports/")
    suspend fun create(@Path("record") record: String, @Header("Idempotency-Key") key: String, @Body body: SummaryExportRequestDto): SummaryExportReceiptDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/document-exports/{export}/retry/")
    suspend fun retryPreview(@Path("record") record: String, @Path("export") export: String): SummaryExportRetryPreviewDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/document-exports/{export}/retry/")
    suspend fun retryExport(@Path("record") record: String, @Path("export") export: String,
        @Header("Idempotency-Key") key: String, @Body body: SummaryExportRetryDto): SummaryExportReceiptDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-notifications/")
    suspend fun notices(@Path("record") record: String): SummaryNoticesDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/meeting-records/{record}/summary-notifications/{notice}/retry/")
    suspend fun retryNotice(@Path("record") record: String, @Path("notice") notice: String,
        @Header("Idempotency-Key") key: String, @Body body: SummaryNoticeRetryDto): SummaryNoticeReceiptDto
}
