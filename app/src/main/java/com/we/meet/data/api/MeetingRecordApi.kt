package com.we.meet.data.api

import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordSnapshotDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/** Canonical record IDs, never the latest summary of a reused room. */
interface MeetingRecordApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/")
    suspend fun records(
        @Query("scope") scope: String = "recent",
        @Query("source_type") source: String? = null,
        @Query("has_summary") hasSummary: Boolean? = null,
        @Query("q") query: String? = null,
        @Query("cursor") cursor: String? = null,
    ): RecordPageDto<RecordDto>

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/")
    suspend fun record(@Path("record") recordId: String): RecordDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/summary-versions/")
    suspend fun summaries(
        @Path("record") recordId: String,
        @Query("cursor") cursor: String? = null,
        @Query("version_id") versionId: String? = null,
    ): RecordPageDto<RecordSummaryVersionDto>

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/transcript-versions/{snapshot}/")
    suspend fun snapshot(
        @Path("record") recordId: String,
        @Path("snapshot") snapshotId: String,
    ): RecordSnapshotDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/resolve/")
    suspend fun resolve(
        @Query("room_id") roomId: String,
        @Query("meeting_session_id") sessionId: String? = null,
    ): RecordDto
}
