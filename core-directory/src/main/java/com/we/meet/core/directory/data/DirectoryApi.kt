package com.we.meet.core.directory.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
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

    // ── 星标联系人 ──────────────────────────────────────────────────────────
    // ⚠️ Like listDepartments this returns a BARE ARRAY (a personal star list is
    // short, so the backend skips the page envelope).

    @GET("api/v1.0/directory/starred/")
    suspend fun listStarred(): List<MemberDto>

    /** Star someone. Idempotent server-side — 201 first time, 200 after. */
    @POST("api/v1.0/directory/starred/")
    suspend fun star(@Body body: Map<String, String>): MemberDto

    /** Unstar. Idempotent — 204 whether or not a star existed. */
    @DELETE("api/v1.0/directory/starred/{userId}/")
    suspend fun unstar(@Path("userId") userId: String)
}
