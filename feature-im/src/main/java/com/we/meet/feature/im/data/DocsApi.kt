package com.we.meet.feature.im.data

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 分享云文档到聊天(入口 A)"我的文档"选择器数据源 —— 代理 we-meet 后端
 * `core/api/search.py` 的 `DocsMyDocumentsView`(与 Web GlobalSearch 的文档
 * 搜索同一 s2s 链路,可见性在 Docs 侧过滤)。`q` 为空即返回最近文档。
 */
internal interface DocsApi {
    @GET("api/v1.0/docs/my-documents/")
    suspend fun myDocuments(@Query("q") q: String? = null): DocsMyDocumentsResponse
}

@JsonClass(generateAdapter = true)
internal data class DocsMyDocumentsResponse(val results: List<DocHit> = emptyList())

/** Public: crosses into ChatViewModel's/DocPickerDialog's public signatures. */
@JsonClass(generateAdapter = true)
data class DocHit(
    val id: String = "",
    val title: String = "",
    val updated_at: String = "",
    val url: String = "",
)
