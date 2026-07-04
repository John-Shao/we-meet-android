package com.we.meet.data.api

import com.we.meet.data.api.dto.ApprovalActRequest
import com.we.meet.data.api.dto.ApprovalInstanceDto
import com.we.meet.data.api.dto.PagedApprovalsDto
import com.we.meet.data.api.dto.PagedTemplatesDto
import com.we.meet.data.api.dto.SubmitApprovalRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Approval (审批) endpoints (core/api/approval.py). Org-scoped server-side.
 * List `role`: "pending" (待我审批) / "mine" (我发起的) / absent (all involved).
 */
interface ApprovalApi {

    @GET("api/v1.0/approval-templates/")
    suspend fun listTemplates(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
    ): PagedTemplatesDto

    @GET("api/v1.0/approvals/")
    suspend fun listApprovals(
        @Query("role") role: String? = null,
        @Query("page") page: Int = 1,
    ): PagedApprovalsDto

    @POST("api/v1.0/approvals/")
    suspend fun submit(@Body body: SubmitApprovalRequest): ApprovalInstanceDto

    @POST("api/v1.0/approvals/{id}/act/")
    suspend fun act(@Path("id") id: String, @Body body: ApprovalActRequest): ApprovalInstanceDto

    @POST("api/v1.0/approvals/{id}/cancel/")
    suspend fun cancel(@Path("id") id: String, @Body body: Map<String, String> = emptyMap()): ApprovalInstanceDto

    @POST("api/v1.0/approvals/{id}/urge/")
    suspend fun urge(@Path("id") id: String, @Body body: Map<String, String> = emptyMap()): ApprovalInstanceDto
}
