package com.we.meet.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 搜索统一 M2:Docs 文档搜索代理(we-meet 后端 `GET /api/v1.0/docs/search/`,
 * sub=调用者服务端注入;可见性在 Docs fork 侧过滤,与本人登录 Docs 同口径)。
 * 后端在 Docs 未配置/不可达时返回空列表——App 侧无需特判降级。
 */
interface SearchApi {

    @GET("api/v1.0/docs/search/")
    suspend fun searchDocs(@Query("q") q: String): DocsSearchResponse
}

@JsonClass(generateAdapter = true)
data class DocsSearchResponse(
    val results: List<DocsSearchHitDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DocsSearchHitDto(
    val id: String,
    val title: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
    /** Docs 站深链(后端拼好),App 内 WebView 直载。 */
    val url: String = "",
)
