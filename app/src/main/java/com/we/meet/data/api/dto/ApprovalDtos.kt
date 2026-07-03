package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Approval (审批) DTOs — mirror core/api/approval.py. Everything except ids is
 * nullable/defaulted (Moshi reflection throws on missing non-null fields; the
 * `flow` node rules are resolved server-side and intentionally omitted here).
 */

@JsonClass(generateAdapter = true)
data class UserLiteDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: ""
}

@JsonClass(generateAdapter = true)
data class ApprovalFormFieldDto(
    val key: String = "",
    val label: String? = null,
    /** text | textarea | number | date (default text). */
    val type: String? = null,
    val required: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ApprovalFormSchemaDto(
    val fields: List<ApprovalFormFieldDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ApprovalTemplateDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @Json(name = "form_schema") val formSchema: ApprovalFormSchemaDto? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class ApprovalTaskDto(
    @Json(name = "node_index") val nodeIndex: Int = 0,
    val approver: UserLiteDto? = null,
    /** pending | approved | rejected. */
    val action: String = "pending",
    val comment: String = "",
    @Json(name = "acted_at") val actedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class ApprovalInstanceDto(
    val id: String,
    val template: String? = null,
    @Json(name = "template_name") val templateName: String? = null,
    val applicant: UserLiteDto? = null,
    /** Free-form submitted values (key → string/number/...). */
    @Json(name = "form_data") val formData: Map<String, Any?> = emptyMap(),
    /** pending | approved | rejected | cancelled | needs_assignment. */
    val status: String = "pending",
    @Json(name = "current_node") val currentNode: Int = 0,
    val tasks: List<ApprovalTaskDto> = emptyList(),
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class PagedTemplatesDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<ApprovalTemplateDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PagedApprovalsDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<ApprovalInstanceDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SubmitApprovalRequest(
    val template: String,
    @Json(name = "form_data") val formData: Map<String, String>,
)

@JsonClass(generateAdapter = true)
data class ApprovalActRequest(
    /** approved | rejected. */
    val action: String,
    val comment: String = "",
)
