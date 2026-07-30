package com.we.meet.core.directory.data

import retrofit2.http.Body
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
    ): PagedMembersDto

    @GET("api/v1.0/directory/members/")
    suspend fun listMembers(
        @Query("q") query: String? = null,
        @Query("department") department: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): PagedMembersDto

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
}
