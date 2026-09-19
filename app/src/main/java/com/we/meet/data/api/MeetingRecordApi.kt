package com.we.meet.data.api

import com.we.meet.data.api.dto.RecordAttributionCandidatePageDto
import com.we.meet.data.api.dto.RecordAttributionRequest
import com.we.meet.data.api.dto.RecordCorrectionDto
import com.we.meet.data.api.dto.RecordCorrectionRequest
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.api.dto.RecordPageDto
import com.we.meet.data.api.dto.RecordSnapshotDto
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import com.we.meet.data.api.dto.RecordOriginalSegmentDto
import com.we.meet.data.api.dto.RecordOnlineTranscriptDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PATCH
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Streaming
import retrofit2.http.Url
import com.we.meet.data.api.dto.RecordTitleRequestDto
import okhttp3.ResponseBody

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
        @Query("is_ongoing") isOngoing: Boolean? = null,
    ): RecordPageDto<RecordDto>

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/")
    suspend fun record(@Path("record") recordId: String): RecordDto

    @Headers("Cache-Control: no-store")
    @PATCH("api/v1.0/meeting-records/{record}/title/")
    suspend fun rename(@Path("record") recordId: String, @Body body: RecordTitleRequestDto): RecordDto

    /** Sign a whole-file read for an imported recording. */
    @GET("api/v1.0/meeting-records/{record}/media/")
    suspend fun media(@Path("record") recordId: String): RecordMediaDto

    /**
     * Download the transcript as a file.
     *
     * `@Url` rather than a path template because the caller builds the URL with
     * its `as=` selector; the auth interceptor still signs it like any other
     * request, which is why a plain browser link cannot be used here.
     */
    @Streaming
    @GET
    suspend fun transcriptExport(@Url url: String): ResponseBody

    /**
     * Correct one transcript segment. The write appends a revision and never
     * rewrites the original, so a reader can always see what ASR produced.
     */
    @Headers("Cache-Control: no-store")
    @PATCH("api/v1.0/meeting-records/{record}/original-segments/{segment}/")
    suspend fun correctOriginal(
        @Path("record") recordId: String,
        @Path("segment") segmentId: String,
        @Body body: RecordCorrectionRequest,
    ): RecordCorrectionDto

    /** Drop every correction for one segment, restoring the recogniser's text. */
    @Headers("Cache-Control: no-store")
    @DELETE("api/v1.0/meeting-records/{record}/original-segments/{segment}/")
    suspend fun revertOriginal(
        @Path("record") recordId: String,
        @Path("segment") segmentId: String,
    ): RecordCorrectionDto

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
    @GET("api/v1.0/meeting-records/{record}/transcripts/")
    suspend fun transcripts(
        @Path("record") recordId: String,
        @Query("expected_revision") revision: Int,
        @Query("q") query: String?,
        @Query("cursor") cursor: String?,
    ): RecordPageDto<RecordOnlineTranscriptDto>

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/original-segments/")
    suspend fun originals(
        @Path("record") recordId: String,
        @Query("expected_revision") revision: Int,
        @Query("q") query: String?,
        @Query("speaker_id") speakerId: String?,
        @Query("cursor") cursor: String?,
    ): RecordPageDto<RecordOriginalSegmentDto>

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/speakers/")
    suspend fun speakers(
        @Path("record") recordId: String,
        @Query("cursor") cursor: String?,
    ): RecordPageDto<RecordSpeakerDto>

    /**
     * Bind one diarised speaker track to a member, or clear the binding.
     *
     * A null `user_id` clears it: attributing the wrong colleague has to be
     * undoable, and the recogniser's own label is still underneath.
     */
    @Headers("Cache-Control: no-store")
    @PATCH("api/v1.0/meeting-records/{record}/speakers/{speaker}/")
    suspend fun attributeSpeaker(
        @Path("record") recordId: String,
        @Path("speaker") speakerId: String,
        @Body body: RecordAttributionRequest,
    ): RecordSpeakerDto

    /** People this reader may bind a track to, drawn from the record's directory. */
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/attribution-candidates/")
    suspend fun attributionCandidates(
        @Path("record") recordId: String,
        @Query("q") query: String?,
    ): RecordAttributionCandidatePageDto

    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/resolve/")
    suspend fun resolve(
        @Query("room_id") roomId: String,
        @Query("meeting_session_id") sessionId: String? = null,
    ): RecordDto
}
