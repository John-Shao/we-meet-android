package com.we.meet.data.api

import com.we.meet.data.api.dto.*
import retrofit2.http.*

interface TranslationArchiveApi {
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/translation-archives/")
    suspend fun archives(@Path("record") record: String, @Query("cursor") cursor: String?): TranslationArchivePageDto
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/meeting-records/{record}/translation-segments/")
    suspend fun segments(@Path("record") record: String, @Query("archive_id") archive: String, @Query("cursor") cursor: String?): TranslationSegmentPageDto
}
