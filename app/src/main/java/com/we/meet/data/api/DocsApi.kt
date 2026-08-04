package com.we.meet.data.api

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 云文档的登录态引导:换一条「带着登录态进 Docs」的 URL。
 *
 * 云文档 tab 原来经 Docs 的 OIDC `authenticate` 端点进站,拿的是 WebView cookie 罐里
 * 那份 **Keycloak 会话 cookie**。但那份 cookie 不是我们的登录态:它随 KC 会话过期、
 * 也不随 App 的 token 续期;一旦没有,云文档 tab 就变成一张 Keycloak 手机号登录页 ——
 * 其它 tab 全都登着,唯独云文档要求再登一次。
 *
 * 这个端点由 we-meet 后端(用 App 自己的 Bearer 登录态认人)去 Docs 换一张 60 秒、
 * 单次使用的票据,拼成可直接加载的 URL;WebView 载过去就拿到 Docs 会话,全程不碰
 * Keycloak。后端未接 Docs / Docs 不可达时 `url` 为 null,调用方退回原来的
 * authenticate 进站方式。
 */
interface DocsApi {

    @POST("api/v1.0/docs/session/")
    suspend fun createSession(@Body body: DocsSessionRequest): DocsSessionResponse
}

@JsonClass(generateAdapter = true)
data class DocsSessionRequest(
    /** Docs 站内落地路径(可带 query),如 `/` 或 `/docs/<id>/?embed=1`。 */
    val next: String,
)

@JsonClass(generateAdapter = true)
data class DocsSessionResponse(
    val url: String? = null,
)
