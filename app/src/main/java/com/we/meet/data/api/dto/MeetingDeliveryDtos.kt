package com.we.meet.data.api.dto

import com.squareup.moshi.Json

data class SummaryExportSelectionDto(@Json(name = "source_kind") val sourceKind: String,
    @Json(name = "source_id") val sourceId: String, val language: String)
data class SummaryExportPreviewDto(val title: String, val markdown: String,
    @Json(name = "payload_hash") val payloadHash: String, @Json(name = "source_kind") val sourceKind: String,
    @Json(name = "source_id") val sourceId: String, val language: String) {
    override fun toString() = "SummaryExportPreviewDto(<private>)"
}
data class SummaryExportDto(val id: String, @Json(name = "source_kind") val sourceKind: String,
    @Json(name = "source_id") val sourceId: String, val language: String, val status: String, val attempt: Int,
    @Json(name = "document_id") val documentId: String?, @Json(name = "can_open") val canOpen: Boolean,
    @Json(name = "error_code") val errorCode: String, @Json(name = "created_at") val createdAt: String)
data class SummaryExportsDto(val available: Boolean = false, val results: List<SummaryExportDto> = emptyList())
data class SummaryExportReceiptDto(val export: SummaryExportDto, val replayed: Boolean)
data class SummaryExportRetryPreviewDto(val export: SummaryExportDto, val title: String, val markdown: String,
    @Json(name = "payload_hash") val payloadHash: String) {
    override fun toString() = "SummaryExportRetryPreviewDto(<private>)"
}
data class SummaryExportRequestDto(@Json(name = "source_kind") val sourceKind: String,
    @Json(name = "source_id") val sourceId: String, val language: String, @Json(name = "expected_hash") val expectedHash: String)
data class SummaryExportRetryDto(@Json(name = "expected_attempt") val expectedAttempt: Int,
    @Json(name = "expected_hash") val expectedHash: String)
data class SummaryExportRetryIntentDto(val exportId: String, val selection: SummaryExportSelectionDto, val request: SummaryExportRetryDto)
data class SummaryNoticeDto(val id: String, @Json(name = "summary_id") val summaryId: String, val status: String,
    val attempt: Int, @Json(name = "error_code") val errorCode: String, @Json(name = "created_at") val createdAt: String)
data class SummaryNoticeRecipientDto(val id: String, val name: String) {
    override fun toString() = "SummaryNoticeRecipientDto(<private>)"
}
data class SummaryNoticesDto(val available: Boolean = false, val strategy: String,
    @Json(name = "legacy_delivery_unchanged") val legacyDeliveryUnchanged: Boolean,
    val results: List<SummaryNoticeDto> = emptyList(),
    @Json(name = "future_recipients") val futureRecipients: List<SummaryNoticeRecipientDto>? = null,
    @Json(name = "policy_error") val policyError: String? = null)
data class SummaryNoticeRetryDto(@Json(name = "expected_attempt") val expectedAttempt: Int)
data class SummaryNoticeRetryIntentDto(val noticeId: String, val summaryId: String, val request: SummaryNoticeRetryDto)
data class SummaryNoticeReceiptDto(val notification: SummaryNoticeDto, val replayed: Boolean)
