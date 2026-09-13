package com.we.meet.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Streaming

interface CapturePlaybackApi {
    @Streaming
    @Headers("Cache-Control: no-store")
    @GET("api/v1.0/capture-sessions/{capture}/audio/{chunk}/")
    suspend fun chunk(@Path("capture") capture: String, @Path("chunk") chunk: String): ResponseBody
}
