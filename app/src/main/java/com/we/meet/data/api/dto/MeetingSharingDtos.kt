package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class SummarySharePersonDto(val id: String, val name: String) { override fun toString() = "SummarySharePersonDto(<private>)" }
data class SummaryShareAccessDto(val id: String, val name: String, val active: Boolean,
    @Json(name = "read_summary") val readSummary: Boolean, @Json(name = "read_transcript") val readTranscript: Boolean) {
    override fun toString() = "SummaryShareAccessDto(<private>)"
}
data class SummaryShareAccessPageDto(val available: Boolean = false, @Json(name = "can_manage") val canManage: Boolean = false,
    val results: List<SummaryShareAccessDto> = emptyList(), @Json(name = "next_cursor") val nextCursor: String? = null)
data class SummaryShareSelectionDto(@Json(name = "user_ids") val userIds: List<String>, val operation: String)
data class SummaryShareRequestDto(@Json(name = "user_ids") val userIds: List<String>, val operation: String,
    @Json(name = "expected_hash") val expectedHash: String)
data class SummaryShareRecipientDto(val id: String, val name: String, val active: Boolean,
    @Json(name = "explicit_summary") val explicitSummary: Boolean, @Json(name = "explicit_transcript") val explicitTranscript: Boolean,
    @Json(name = "effective_summary") val effectiveSummary: Boolean, @Json(name = "effective_transcript") val effectiveTranscript: Boolean,
    @Json(name = "inherited_summary") val inheritedSummary: Boolean, @Json(name = "after_explicit_summary") val afterExplicitSummary: Boolean,
    @Json(name = "after_effective_summary") val afterEffectiveSummary: Boolean,
    @Json(name = "grant_id") val grantId: String?, @Json(name = "grant_updated_at") val grantUpdatedAt: String?) {
    override fun toString() = "SummaryShareRecipientDto(<private>)"
}
data class SummarySharePreviewDto(@Json(name = "record_id") val recordId: String, val title: String,
    @Json(name = "organization_id") val organizationId: String?, val operation: String, val scope: String,
    val recipients: List<SummaryShareRecipientDto>, @Json(name = "grants_originals") val grantsOriginals: Boolean,
    @Json(name = "grants_media") val grantsMedia: Boolean, @Json(name = "changes_document_permissions") val changesDocumentPermissions: Boolean,
    @Json(name = "sends_messages") val sendsMessages: Boolean, @Json(name = "preview_hash") val previewHash: String) {
    override fun toString() = "SummarySharePreviewDto(<private>)"
}
data class SummaryShareReceiptDto(@Json(name = "request_id") val requestId: String, val replayed: Boolean,
    @Json(name = "applied_preview") val appliedPreview: SummarySharePreviewDto)
