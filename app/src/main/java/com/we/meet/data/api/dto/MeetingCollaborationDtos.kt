package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class MaterialMemberDto(val id: String, val name: String, val role: String, val active: Boolean = true,
    @Json(name = "avatar_url") val avatarUrl: String? = null)
data class MaterialAccessDto(val scope: String, @Json(name = "record_id") val recordId: String,
    val revision: Int, @Json(name = "can_manage") val canManage: Boolean = false,
    @Json(name = "is_owner") val isOwner: Boolean = false,
    @Json(name = "link_scope") val linkScope: String,
    @Json(name = "can_link_organization") val canLinkOrganization: Boolean = false,
    val results: List<MaterialMemberDto>, val count: Int, @Json(name = "can_notify") val canNotify: Boolean = false, @Json(name = "pending_notifications") val pendingNotifications: Int = 0)
data class MaterialPersonDto(val id: String, val name: String, @Json(name = "avatar_url") val avatarUrl: String? = null)
data class MaterialCandidatesDto(val results: List<MaterialPersonDto>, @Json(name = "next_cursor") val nextCursor: String? = null)
data class MaterialRoleDto(val id: String, val role: String = "reader")
data class MaterialChangeDto(val operation: String, @Json(name = "expected_revision") val expectedRevision: Int,
    val members: List<MaterialRoleDto>? = null, @Json(name = "link_scope") val linkScope: String? = null, val notify: Boolean? = null, val note: String? = null)
data class MaterialReceiptDto(val scope: String, @Json(name = "record_id") val recordId: String, val revision: Int, val replayed: Boolean)
