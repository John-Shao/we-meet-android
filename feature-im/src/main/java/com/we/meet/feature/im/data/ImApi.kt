package com.we.meet.feature.im.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * we-meet backend IM bridge endpoint surface (core/api/im.py). All requests ride
 * the host's authed OkHttp (OIDC bearer auto-attached); the backend resolves the
 * caller from the token and talks HMAC-admin to jusi-light-im.
 *
 * Request bodies are plain Maps: every endpoint takes a small JSON object whose
 * keys vary by call (`peer_uid` vs `peer_user_id`, optional fields), and a Map
 * keeps the partial-body semantics obvious.
 */
internal interface ImApi {

    @GET("api/v1.0/im/preferences/")
    suspend fun inputPreferences(): ImPreferenceDto

    @PATCH("api/v1.0/im/preferences/")
    suspend fun saveInputPreferences(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): ImPreferenceDto

    @GET("api/v1.0/im/custom-emojis/")
    suspend fun customEmojis(): List<ImCustomEmojiDto>

    /** Mint a client-bound IM JWT. Empty body — identity comes from the bearer. */
    @POST("api/v1.0/im/token/")
    suspend fun fetchToken(@Body body: Map<String, String> = emptyMap()): ImTokenResponse

    /**
     * Create-or-get a 1:1 conversation. Body is either `{"peer_uid": <jusi uid>}`
     * or `{"peer_user_id": <we-meet uuid>}` (contact-picker flow — backend
     * resolves the IM uid server-side). Deterministic cid per sorted pair.
     */
    @POST("api/v1.0/im/conversations/direct/")
    suspend fun createDirectConversation(
        @Body body: Map<String, String>,
    ): ImDirectConversationResponse

    /** Create a group: `{"member_user_ids": [...], "name": ...}`. Caller becomes owner. */
    @POST("api/v1.0/im/conversations/group/")
    suspend fun createGroupConversation(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): ImGroupConversationResponse

    /** P9 拉人: `{"cid": ..., "member_user_ids": [...]}`. Any member may add. */
    @POST("api/v1.0/im/conversations/add-members/")
    suspend fun addMembers(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, Any>

    /** P9 踢人 (owner-only): `{"cid": ..., "member_user_id": ...}`. */
    // 卡片按钮(A2)。**请求里没有 cid,也没有 value** —— 服务端按 mid 查
    // 自己的记录拿权威 cid 再验成员资格。客户端说的一概不算。
    @POST("api/v1.0/im/cards/{mid}/click/")
    suspend fun clickCardButton(
        @retrofit2.http.Path("mid") mid: Long,
        @Body body: Map<String, String>,
    ): Map<String, @JvmSuppressWildcards Any>

    @POST("api/v1.0/im/cards/states/")
    suspend fun cardStates(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, @JvmSuppressWildcards Any>

    @POST("api/v1.0/im/conversations/remove-member/")
    suspend fun removeMember(@Body body: Map<String, String>): Map<String, Any>

    /**
     * Rename / re-describe a group (owner-only). jusi stores meta wholesale, so
     * always send the complete desired meta: `{"cid", "name", "description", "kind"}`
     * where kind ∈ rename | description (picks the announced system message).
     */
    @POST("api/v1.0/im/conversations/update/")
    suspend fun updateGroupMeta(@Body body: Map<String, String>): Map<String, Any>

    /** P9.1: post an "X 退出群聊" system message just before leaving. Best-effort. */
    @POST("api/v1.0/im/conversations/announce-leave/")
    suspend fun announceLeave(@Body body: Map<String, String>): Map<String, Any>

    /** Map IM uids → we-meet display identities. Body `{"im_uids": [...]}`. */
    @POST("api/v1.0/im/users/resolve/")
    suspend fun resolveUsers(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, ImUserInfo>

    /** P4: map OIDC subs (= LiveKit identities) → display identities for the
     * in-call multi-party grid. Body `{"subs": [...]}`. */
    @POST("api/v1.0/im/users/resolve-subs/")
    suspend fun resolveSubs(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, ImUserInfo>

    /** Presigned PUT for a chat image. Body `{"content_type": ..., "size": ...}`. */
    @POST("api/v1.0/im/images/upload-url/")
    suspend fun imageUploadUrl(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): UploadUrlResponse

    /** Presigned PUT for a chat file (≤50 MiB). Body `{"name", "content_type", "size"}`. */
    @POST("api/v1.0/im/files/upload-url/")
    suspend fun fileUploadUrl(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): UploadUrlResponse

    /** Presigned PUT for a voice clip (≤20 MiB) — declared for IM Phase 2. */
    @POST("api/v1.0/im/audio/upload-url/")
    suspend fun audioUploadUrl(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): UploadUrlResponse

    /**
     * Map object keys → short-lived presigned GET URLs (~1h). Content-agnostic:
     * routes `chat/` → image bucket, `file/` → doc bucket, `audio/` → voice bucket.
     */
    @POST("api/v1.0/im/images/resolve/")
    suspend fun resolveMedia(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, String>

    /**
     * 分享云文档到聊天:给目标会话成员对文档授只读。Body `{doc_id, cids:[...]}`。
     * 后端权威解析会话成员 → sub/email → 调 Docs s2s 精准授权。best-effort。
     */
    @POST("api/v1.0/im/grant-doc-access/")
    suspend fun grantDocAccess(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, Any>

    /** P1-M3 消息全文检索(代理 jusi p15;仅本人可见范围,已撤回排除)。 */
    @GET("api/v1.0/im/search/")
    suspend fun searchMessages(
        @Query("q") q: String,
        @Query("cid") cid: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("before_mid") beforeMid: Long? = null,
    ): ImSearchResponse

    // ---- 群机器人(对标飞书)。REST 资源而非 action 风格:双端都要标准 CRUD。
    //      建/改/删/看凭据都是群主 only,后端判定;列表全体成员可读。 ----

    /** 本群机器人列表。非群主拿到的凭据字段是 null。 */
    @GET("api/v1.0/im/bots/")
    suspend fun listBots(@Query("cid") cid: String): List<ImBotDto>

    /** 建自定义机器人:`{cid, name, description, avatar_color_index}`。 */
    @POST("api/v1.0/im/bots/")
    suspend fun createBot(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): ImBotDto

    /** 改名/改描述/改头像色/开关三道闸门/停用。只发要改的键。 */
    @PATCH("api/v1.0/im/bots/{id}/")
    suspend fun updateBot(
        @Path("id") id: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): ImBotDto

    /** 移除机器人。内置助手返回 400 —— 它们只能停用。 */
    @DELETE("api/v1.0/im/bots/{id}/")
    suspend fun deleteBot(@Path("id") id: String)

    /** 按需取签名密钥(列表刻意不带),后端记一条审计。 */
    @GET("api/v1.0/im/bots/{id}/secret/")
    suspend fun fetchBotSecret(@Path("id") id: String): ImBotSecretResponse

    /**
     * 出站回调的验签密钥(A3)。与上面那把是**两把**:入站那把验第三方发进来的,
     * 这把让第三方验我们发出去的。没有它出站签名就只是装饰。
     *
     * 没配回调地址时 404 —— 密钥还没铸出来,回空串会被抄进对方的配置里。
     */
    @GET("api/v1.0/im/bots/{id}/callback-secret/")
    suspend fun fetchBotCallbackSecret(@Path("id") id: String): ImBotSecretResponse

    /** 重置签名密钥,旧密钥立即失效。 */
    @POST("api/v1.0/im/bots/{id}/reset-secret/")
    suspend fun resetBotSecret(@Path("id") id: String): ImBotSecretResponse
}
