package com.we.meet.feature.im.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

    /** Batch-delete messages: `{"cid": ..., "mids": [...]}`. Any member may delete. */
    @POST("api/v1.0/im/messages/delete/")
    suspend fun deleteMessages(
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): Map<String, Any>

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
}
