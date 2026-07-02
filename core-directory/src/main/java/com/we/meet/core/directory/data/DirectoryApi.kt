package com.we.meet.core.directory.data

import retrofit2.http.GET
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
}
