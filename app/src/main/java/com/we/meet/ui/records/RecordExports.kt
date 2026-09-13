package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.MeetingIntentKind
import com.we.meet.data.repository.MeetingDeliveryRepository
import com.we.meet.data.repository.MeetingReviewRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

internal data class ExportSource(val kind: String, val id: String, val label: String)
private data class ExportPreviewTarget(val source: ExportSource? = null, val language: String = "zh", val retryId: String? = null)
private data class ExportPreview(val title: String, val markdown: String, val hash: String, val selection: SummaryExportSelectionDto, val retry: SummaryExportDto? = null)

@Composable
internal fun RecordExports(viewer: String, record: RecordDto, versions: List<RecordSummaryVersionDto>, repository: MeetingDeliveryRepository,
    reviews: MeetingReviewRepository, currentViewer: () -> String?, onDocument: (String) -> Unit) {
    if (!record.capabilities.readSummary) return
    val human = visibleRead(viewer, record.id) { reviews.current(viewer, record.id) }?.getOrNull()?.current
    val sources = buildList {
        human?.let { add(ExportSource("human", it.id, stringResource(R.string.human_summary_version, it.revision) + " · " + recordTime(it.createdAt))) }
        versions.forEach { version -> add(ExportSource("ai", version.id,
            stringResource(when (version.stage) { "quick" -> R.string.records_quick; "realtime" -> R.string.records_live; else -> R.string.records_final }) + " · " + recordTime(version.createdAt))) }
    }
    RecordExportWorkspace(viewer, record.id, sources, repository, currentViewer, onDocument)
}

@Composable
internal fun RecordExportWorkspace(viewer: String, recordId: String, sources: List<ExportSource>, repository: MeetingDeliveryRepository,
    currentViewer: () -> String?, onDocument: (String) -> Unit) {
    val actions = rememberDeliveryActions(viewer, recordId, repository, currentViewer)
    var choose by remember(viewer, recordId) { mutableStateOf(false) }
    var language by remember(viewer, recordId) { mutableStateOf("zh") }
    var target by remember(viewer, recordId) { mutableStateOf<ExportPreviewTarget?>(null) }
    var accepted by remember(viewer, recordId) { mutableStateOf(false) }
    val read = visibleRead(viewer, recordId, actions.refresh, intervalMs = 5000) { repository.exports(viewer, recordId) }
    val state = read?.getOrNull()
    val pending = actions.pending.filterKeys { it == MeetingIntentKind.DOCUMENT_EXPORT || it == MeetingIntentKind.DOCUMENT_EXPORT_RETRY }
    val preview = target?.let { selection -> visibleRead(viewer, recordId, selection, actions.refresh) {
        if (selection.retryId != null) repository.retryPreview(viewer, recordId, selection.retryId).map {
            ExportPreview(it.title, it.markdown, it.payloadHash, MeetingDeliveryRepository.selection(it.export), it.export)
        } else {
            val source = requireNotNull(selection.source)
            repository.preview(viewer, recordId, SummaryExportSelectionDto(source.kind, source.id, selection.language)).map {
                ExportPreview(it.title, it.markdown, it.payloadHash, SummaryExportSelectionDto(it.sourceKind, it.sourceId, it.language))
            }
        }
    } }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.record_export_title), style = MaterialTheme.typography.titleMedium)
            when {
                read == null -> WeMeetInlineLoading()
                read.isFailure -> WeMeetInlineErrorState(onRetry = { actions.refresh++ }, message = stringResource(R.string.record_export_read_error))
                state != null -> {
                    if (actions.storageError) WeMeetInlineErrorState(onRetry = { actions.storageRetry++ }, message = stringResource(R.string.summary_controls_storage_error))
                    if (!state.available) Text(stringResource(R.string.record_export_unavailable))
                    if (pending.isEmpty()) TextButton(onClick = { choose = true; accepted = false; actions.error = false }, enabled = state.available && actions.enabled && sources.isNotEmpty()) {
                        Text(stringResource(R.string.record_export_choose))
                    }
                    pending.forEach { (kind, intent) ->
                        Text(stringResource(R.string.record_export_unknown))
                        Button(onClick = { actions.perform { controller ->
                            if (kind == MeetingIntentKind.DOCUMENT_EXPORT) controller.create(recordId, requireNotNull(MeetingDeliveryRepository.createAdapter.fromJson(intent.body)))
                            else controller.retryExport(recordId, requireNotNull(MeetingDeliveryRepository.exportRetryAdapter.fromJson(intent.body)))
                            target = null; accepted = true
                        } }, enabled = actions.enabled) { Text(stringResource(if (kind == MeetingIntentKind.DOCUMENT_EXPORT) R.string.record_export_reconcile_create else R.string.record_export_reconcile_retry)) }
                    }
                    if (accepted) Text(stringResource(R.string.record_export_accepted))
                    if (state.results.isEmpty()) Text(stringResource(R.string.record_export_empty))
                    state.results.forEach { row ->
                        HorizontalDivider()
                        Text(sources.find { it.id == row.sourceId && it.kind == row.sourceKind }?.label ?: stringResource(if (row.sourceKind == "human") R.string.record_export_human else R.string.record_export_ai))
                        Text(stringResource(R.string.record_export_details, recordTime(row.createdAt), stringResource(if (row.language == "zh") R.string.record_export_zh else R.string.record_export_en), row.attempt), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(exportStatus(row.status)), style = MaterialTheme.typography.labelLarge)
                        if (row.canOpen && row.documentId != null) TextButton(onClick = { onDocument(row.documentId) }, enabled = !actions.busy) { Text(stringResource(R.string.record_export_open)) }
                        if (state.available && pending.isEmpty() && retryable(row)) TextButton(onClick = { target = ExportPreviewTarget(retryId = row.id); actions.error = false; accepted = false }, enabled = actions.enabled) {
                            Text(stringResource(R.string.record_export_retry_preview))
                        }
                    }
                    if (actions.busy) WeMeetInlineLoading()
                    if (actions.error) Text(stringResource(R.string.record_export_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (choose && state?.available == true && actions.ready && pending.isEmpty()) AlertDialog(onDismissRequest = { choose = false },
        title = { Text(stringResource(R.string.record_export_choose)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.record_export_language_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    listOf("zh" to R.string.record_export_zh, "en" to R.string.record_export_en).forEach { (value, label) -> FilterChip(selected = language == value, onClick = { language = value }, label = { Text(stringResource(label)) }) }
                }
                sources.forEach { source -> TextButton(onClick = { target = ExportPreviewTarget(source, language); choose = false }) { Text(source.label) } }
            }
        }, confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.records_close)) } })
    if (target != null && state != null && actions.ready && pending.isEmpty()) {
        val value = preview?.getOrNull()
        val retry = value?.retry
        val validRetry = retry == null || (retryable(retry) && state.results.find { it.id == retry.id }?.let { it.attempt == retry.attempt && retryable(it) } == true)
        AlertDialog(onDismissRequest = { if (!actions.busy) target = null }, title = { Text(stringResource(R.string.record_export_preview)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.record_export_effect), style = MaterialTheme.typography.bodySmall)
                if (target?.retryId != null) Text(stringResource(R.string.record_export_frozen), style = MaterialTheme.typography.bodySmall)
                when {
                    preview == null -> WeMeetInlineLoading()
                    preview.isFailure -> WeMeetInlineErrorState(onRetry = { actions.refresh++ }, message = stringResource(R.string.record_export_read_error))
                    value != null -> {
                        Text(value.title, style = MaterialTheme.typography.titleSmall)
                        // Literal, bounded text only: preview cannot fetch embedded images or open model-generated links.
                        val parts = remember(value.markdown) { previewParts(value.markdown) }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = Dimens.SheetContentMaxHeight)) { items(parts) { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        if (!validRetry) Text(stringResource(R.string.record_export_changed))
                        if (actions.error) Text(stringResource(R.string.record_export_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { value?.let { copy -> actions.perform { controller ->
            if (copy.retry != null) controller.retryExport(recordId, SummaryExportRetryIntentDto(copy.retry.id, copy.selection, SummaryExportRetryDto(copy.retry.attempt, copy.hash)))
            else controller.create(recordId, SummaryExportRequestDto(copy.selection.sourceKind, copy.selection.sourceId, copy.selection.language, copy.hash))
            target = null; accepted = true
        } } }, enabled = actions.enabled && state.available && value != null && validRetry) { Text(stringResource(if (target?.retryId == null) R.string.record_export_confirm else R.string.record_export_confirm_retry)) } },
            dismissButton = { TextButton(onClick = { target = null }, enabled = !actions.busy) { Text(stringResource(R.string.records_close)) } })
    }
}

private fun retryable(row: SummaryExportDto) = row.status in setOf("failed", "uncertain", "canceled") && row.attempt < 20 && row.errorCode != "document_conflict"
private fun previewParts(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 2000, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        add(text.substring(start, end)); start = end
    }
}
private fun exportStatus(status: String) = when (status) {
    "queued" -> R.string.record_export_queued
    "running" -> R.string.record_export_running
    "ready" -> R.string.record_export_ready
    "uncertain" -> R.string.record_export_uncertain
    "failed" -> R.string.record_export_failed
    "unavailable" -> R.string.record_export_unavailable
    else -> R.string.record_export_canceled
}
