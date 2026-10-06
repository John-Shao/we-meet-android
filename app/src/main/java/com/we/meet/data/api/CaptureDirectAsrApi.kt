package com.we.meet.data.api

import com.we.meet.data.api.dto.CaptureAsrCreatedDto
import com.we.meet.data.api.dto.CaptureAsrJobDto
import com.squareup.moshi.Json
import okhttp3.RequestBody
import retrofit2.http.*

data class DirectAsrRange(@Json(name="start_ms") val startMs: Long, @Json(name="end_ms") val endMs: Long)
data class DirectAsrFinal(@Json(name="ingest_id") val ingestId: String, val sequence: Int,
    @Json(name="start_ms") val startMs: Long, @Json(name="end_ms") val endMs: Long,
    val text: String, val language: String = "") {
    override fun toString() = "DirectAsrFinal(<private>)"
}
data class DirectAsrStart(val device_id: String, val expected_job_id: String?)
data class DirectAsrSync(val device_id: String, val operation: String, val ranges: List<DirectAsrRange>,
    val finals: List<DirectAsrFinal>, val final_sequence: Int, val complete: Boolean)

interface CaptureDirectAsrApi {
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/transcription/direct/")
    suspend fun start(@Path("capture") capture: String, @Header("X-Capture-Lease") lease: String,
        @Header("Idempotency-Key") key: String, @Body body: RequestBody): CaptureAsrCreatedDto
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/capture-sessions/{capture}/transcription/direct/{job}/")
    suspend fun sync(@Path("capture") capture: String, @Path("job") job: String,
        @Header("X-Capture-Lease") lease: String, @Body body: DirectAsrSync): CaptureAsrJobDto
}
