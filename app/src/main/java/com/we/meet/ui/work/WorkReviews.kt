package com.we.meet.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.we.meet.R
import com.we.meet.data.api.WorkReviewReport
import com.we.meet.ui.theme.Dimens

fun reviewStatus(status: String): Int = when (status) {
    "queued" -> R.string.work_review_queued
    "running" -> R.string.work_review_running
    "succeeded" -> R.string.work_review_succeeded
    "failed" -> R.string.work_review_failed
    "canceled" -> R.string.work_review_canceled
    else -> R.string.work_review_unknown
}

fun reviewVerdict(report: WorkReviewReport): String? =
    if (report.verdict != null && report.missingInformation.isNotEmpty()) "inconclusive" else report.verdict

@Composable
fun WorkReviews(ui: WorkUi) {
    HorizontalDivider()
    Text(stringResource(R.string.work_review_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.work_review_help))
    if (ui.reviewsUnavailable) {
        Text(stringResource(R.string.work_review_unavailable), color = MaterialTheme.colorScheme.error)
        return
    }
    if (!ui.reviewEnabled) Text(stringResource(R.string.work_review_disabled))
    if (ui.reviews.isEmpty() && ui.reviewEnabled && !ui.loading) Text(stringResource(R.string.work_review_empty))
    ui.reviews.forEach { review ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(reviewStatus(review.status)), style = MaterialTheme.typography.titleMedium)
                Text(review.model)
                Text(stringResource(R.string.work_review_selection, review.selection.joinToString { it.name }))
                Text(if (review.inputTokens == null) stringResource(R.string.work_review_reserved, review.reservedTokens)
                     else stringResource(R.string.work_review_usage, review.inputTokens, requireNotNull(review.outputTokens)))
                if (review.errorCode.isNotEmpty()) Text(stringResource(R.string.work_review_delivery_failed), color = MaterialTheme.colorScheme.error)
                reviewVerdict(review.report)?.let { verdict ->
                    Text(stringResource(when (verdict) {
                        "no_issues" -> R.string.work_review_no_issues
                        "needs_changes" -> R.string.work_review_needs_changes
                        "inconclusive" -> R.string.work_review_inconclusive
                        else -> R.string.work_review_verdict_unknown
                    }), style = MaterialTheme.typography.titleSmall)
                }
                if (review.report.summary.isNotEmpty()) Text(review.report.summary)
                review.report.findings.forEach { finding ->
                    Text(stringResource(if (finding.severity == "error") R.string.work_review_issue else R.string.work_review_warning, finding.message))
                    finding.evidence.forEach { evidence ->
                        // Render provider content as text; never as HTML or a navigable URI.
                        Text(evidence.quote)
                        Text(stringResource(R.string.work_review_evidence, evidence.file, evidence.sha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (review.report.missingInformation.isNotEmpty()) {
                    Text(stringResource(R.string.work_review_missing), style = MaterialTheme.typography.titleSmall)
                    review.report.missingInformation.forEach { Text("• $it") }
                }
            }
        }
    }
}
