package com.we.meet.core.directory.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Read-only org-directory endpoints under api/v1.0/directory/, org-scoped server-side. */
interface DirectoryApi {

    // ⚠️ Unlike the member endpoints this returns a BARE ARRAY (no DRF page envelope).
    @GET("api/v1.0/directory/departments/")
    suspend fun listDepartments(): List<DepartmentDto>

    @GET("api/v1.0/directory/departments/{id}/members/")
    suspend fun listDepartmentMembers(
        @Path("id") departmentId: String,
        @Query("include_subtree") includeSubtree: Boolean = true,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        /**
         * `pinyin` = 按拼音序(汉字没有可用的编码序)。不传就是服务端的姓名编码序。
         * 通讯录一律传它:同一份名册在 Web 与 App 上必须是同一个顺序。
         */
        @Query("ordering") ordering: String? = null,
        /**
         * 从某个首字母开始(A–Z 或 '#'):服务端给的是「拼音键 ≥ 起点」,不是
         * 「首字母 == 起点」,所以从这里还能一路往下滚到 Z。见 [listAlphabet]。
         */
        @Query("from_initial") fromInitial: String? = null,
    ): PagedMembersDto

    @GET("api/v1.0/directory/members/")
    suspend fun listMembers(
        @Query("q") query: String? = null,
        @Query("department") department: String? = null,
        /**
         * 只在带 [department] 时有意义:true = 含下级部门。null → 不发送该参数
         * (服务端默认仅直属成员)。
         */
        @Query("include_subtree") includeSubtree: Boolean? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
        @Query("ordering") ordering: String? = null,
        @Query("from_initial") fromInitial: String? = null,
    ): PagedMembersDto

    /**
     * A–Z 索引条:每个字母各有多少人(**只返计数,不下发全册**)。
     *
     * 与列表同一套过滤,所以 [department] + [includeSubtree] 的语义必须和浏览
     * 那一侧完全一致 —— 否则「产品部里 L 有 3 个人」点进去却只有 1 个(或反过来
     * 索引条上没有 L 却搜得到),读起来就是「这个功能不准」。
     */
    @GET("api/v1.0/directory/members/alphabet/")
    suspend fun listAlphabet(
        @Query("department") department: String? = null,
        @Query("include_subtree") includeSubtree: Boolean? = null,
    ): AlphabetDto

    @GET("api/v1.0/directory/members/{userId}/")
    suspend fun getMember(@Path("userId") userId: String): MemberDto

    /**
     * Reveal a member's FULL phone (the only place it's served un-masked).
     * Same-org enforced server-side; revealing another member's number posts a
     * 「对方查看了你的手机号码」 notice into the direct chat. P3.
     */
    @POST("api/v1.0/directory/members/{userId}/reveal-phone/")
    suspend fun revealPhone(@Path("userId") userId: String): RevealPhoneDto

    // ── 逐联系人的两个独立 flag(星标 / 他的消息特别提醒)────────────────────
    // ⚠️ Like listDepartments these return BARE ARRAYS (personal lists are short,
    // so the backend skips the page envelope).

    /** 星标联系人页用的可渲染卡片(按目录顺序,已投影本组织 Membership)。 */
    @GET("api/v1.0/directory/starred/")
    suspend fun listStarred(): List<MemberDto>

    /** 同上,但投影的是「他的消息特别提醒」名单(设置 › 通知 那页用)。 */
    @GET("api/v1.0/directory/special-alert/")
    suspend fun listSpecialAlert(): List<MemberDto>

    /**
     * 两个 flag 的紧凑清单,喂 [ContactPrefs] 的本地集合。
     *
     * 与 [listStarred] 分开是有意的:会话列表要给「从没拉过卡片」的对端打标记,
     * 只需要 id + 布尔;而星标页需要能直接渲染的卡片。
     */
    @GET("api/v1.0/directory/contact-prefs/")
    suspend fun listContactPrefs(): List<ContactPrefDto>

    /**
     * 设置任一 flag。body 里**省略的键服务端不动**,所以拨一个开关不会顺手清掉
     * 另一个。Idempotent;返回的卡片带回两个 flag 的权威值。
     */
    @PUT("api/v1.0/directory/contact-prefs/{userId}/")
    suspend fun setContactPref(
        @Path("userId") userId: String,
        @Body body: Map<String, Boolean>,
    ): MemberDto

    @GET("api/v1.0/directory/external-contacts/")
    suspend fun listExternalContacts(): List<ExternalContactDto>

    @GET("api/v1.0/directory/external-contacts/requests/")
    suspend fun listExternalContactRequests(): List<ExternalContactDto>

    @GET("api/v1.0/directory/external-contacts/search/")
    suspend fun searchExternalAccounts(@Query("q") query: String): List<ExternalContactDto>

    @POST("api/v1.0/directory/external-contacts/requests/")
    suspend fun sendExternalContactRequest(
        @Body body: ExternalContactRequestBody,
    ): ExternalContactDto

    @POST("api/v1.0/directory/external-contacts/{id}/accept/")
    suspend fun acceptExternalContactRequest(@Path("id") id: String): ExternalContactDto

    @POST("api/v1.0/directory/external-contacts/{id}/decline/")
    suspend fun declineExternalContactRequest(@Path("id") id: String): ExternalContactDto

    @DELETE("api/v1.0/directory/external-contacts/{id}/")
    suspend fun removeExternalContact(@Path("id") id: String)
}
