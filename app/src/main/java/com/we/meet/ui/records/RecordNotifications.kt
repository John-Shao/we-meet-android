package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.repository.MeetingDeliveryRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

@Composable
internal fun RecordNotifications(viewer: String, record: RecordDto, repository: MeetingDeliveryRepository,
    currentViewer: () -> String?, onSummary: (String) -> Unit) {
    if (!record.capabilities.readSummary) return
    val actions = rememberDeliveryActions(viewer, record.id, repository, currentViewer)
    var confirmation by remember(viewer, record.id) { mutableStateOf<SummaryNoticeDto?>(null) }
    val read = visibleRead(viewer, record.id, actions.refresh, intervalMs = 5000) { repository.notices(viewer, record.id) }
    val state = read?.getOrNull()
    val pending = actions.pending[MeetingIntentKind.SUMMARY_NOTICE_RETRY]
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.record_notice_title), style = MaterialTheme.typography.titleMedium)
            when {
                read == null -> WeMeetInlineLoading()
                read.isFailure -> WeMeetInlineErrorState(onRetry = { actions.refresh++ }, message = stringResource(R.string.record_notice_read_error))
                state != null -> {
                    Text(stringResource(if (state.strategy == "owner") R.string.record_notice_owner else R.string.record_notice_online))
                    Text(stringResource(R.string.record_notice_policy), style = MaterialTheme.typography.bodySmall)
                    if (state.policyError != null) Text(stringResource(R.string.record_notice_policy_error), color = MaterialTheme.colorScheme.error)
                    else state.futureRecipients?.let { people ->
                        Text(stringResource(R.string.record_notice_future, if (people.isEmpty()) stringResource(R.string.record_notice_no_recipients) else people.joinToString("、") { it.name.ifBlank { it.id } }))
                    }
                    if (!state.available) Text(stringResource(R.string.record_notice_unavailable))
                    if (actions.storageError) WeMeetInlineErrorState(onRetry = { actions.storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                    if (pending != null) {
                        Text(stringResource(R.string.record_notice_unknown))
                        Button(onClick = { actions.perform { controller ->
                            controller.retryNotice(record.id, requireNotNull(MeetingDeliveryRepository.noticeRetryAdapter.fromJson(pending.body)))
                        } }, enabled = actions.enabled) { Text(stringResource(R.string.summary_controls_reconcile)) }
                    }
                    if (state.results.isEmpty()) Text(stringResource(R.string.record_notice_empty))
                    state.results.forEach { notice ->
                        HorizontalDivider()
                        Text(recordTime(notice.createdAt), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(noticeStatus(notice.status)), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.record_notice_attempt, notice.attempt), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onSummary(notice.summaryId) }, enabled = !actions.busy) { Text(stringResource(R.string.record_notice_open_summary)) }
                        if (state.available && pending == null && notice.status in setOf("failed", "uncertain", "canceled") && notice.attempt < 20 && notice.errorCode != "message_conflict") {
                            TextButton(onClick = { confirmation = notice }, enabled = actions.enabled) { Text(stringResource(R.string.record_notice_retry)) }
                        }
                    }
                    if (actions.busy) WeMeetInlineLoading()
                    if (actions.error) Text(stringResource(R.string.record_notice_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (state != null && actions.ready && pending == null) confirmation?.let { notice ->
        val current = state.results.find { it.id == notice.id }
        val matches = state.available && current == notice
        AlertDialog(onDismissRequest = { if (!actions.busy) confirmation = null }, title = { Text(stringResource(R.string.record_notice_confirm)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(recordTime(notice.createdAt)); Text(stringResource(R.string.record_notice_retry_effect))
                if (!matches) Text(stringResource(R.string.record_notice_changed))
                if (actions.error) Text(stringResource(R.string.record_notice_error), color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(onClick = { actions.perform { controller ->
                controller.retryNotice(record.id, SummaryNoticeRetryIntentDto(notice.id, notice.summaryId, SummaryNoticeRetryDto(notice.attempt)))
                confirmation = null
            } }, enabled = actions.enabled && matches) { Text(stringResource(R.string.record_notice_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !actions.busy) { Text(stringResource(R.string.records_close)) } })
    }
}

private fun noticeStatus(status: String) = when (status) {
    "queued" -> R.string.record_notice_queued
    "running" -> R.string.record_notice_running
    "delivered" -> R.string.record_notice_delivered
    "uncertain" -> R.string.record_notice_uncertain
    "failed" -> R.string.record_notice_failed
    "unavailable" -> R.string.record_notice_unavailable
    else -> R.string.record_notice_canceled
}
