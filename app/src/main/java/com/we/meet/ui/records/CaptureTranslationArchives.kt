package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureTranslationArchiveDto
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

/** Read-only owner archive. No recording-device lease, playback seek or persisted text. */
@Composable
internal fun CaptureTranslationArchives(viewer: String, capture: String, record: String, repository: CaptureTranslationRepository) {
    var selection by remember(viewer, capture, record, repository) { mutableStateOf<CaptureTranslationArchiveDto?>(null) }
    var cursors by remember(viewer, capture, record, repository) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember(viewer, capture, record, repository) { mutableIntStateOf(0) }
    val selected = selection
    if (selected != null) {
        CaptureTranslationSegments(viewer, capture, record, repository, selected) { selection = null }
        return
    }
    val result = visibleRead(viewer, capture, record, repository, cursors.last(), refresh, intervalMs = 5000) { repository.archives(viewer, capture, record, cursors.last()) }
    Column(Modifier.fillMaxSize()) {
        RecordPanelToolbar(listOf(RecordToolAction(stringResource(R.string.records_refresh)) { refresh++ }))
        Column(Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding)) {
            Text(stringResource(R.string.archives_description), Modifier.padding(vertical = Dimens.SpaceS), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.archives_private_scope), style = MaterialTheme.typography.bodySmall)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState({ refresh++ }, message = stringResource(R.string.archives_unavailable))
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    if (result.getOrThrow().results.isEmpty()) item { WeMeetEmptyState(stringResource(R.string.archives_empty)) }
                    items(result.getOrThrow().results, key = { it.id }) { archive ->
                        OutlinedCard(onClick = { selection = archive }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                Text(stringResource(if (archive.configuration.mode == "simultaneous") R.string.capture_translation_simultaneous else R.string.capture_translation_speech), style = MaterialTheme.typography.titleSmall)
                                Text(archiveLanguage(archive.configuration.sourceLanguage) + " → " + archiveLanguage(archive.configuration.targetLanguage))
                                Text(stringResource(archiveStatus(archive.status)) + " · " + recordTime(archive.createdAt), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.archives_count, archive.segmentCount), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { ArchivePageButtons(cursors, result.getOrThrow().nextCursor) { cursors = it } }
                }
            }
        }
    }
}

@Composable
private fun CaptureTranslationSegments(viewer: String, capture: String, record: String, repository: CaptureTranslationRepository, selected: CaptureTranslationArchiveDto, back: () -> Unit) {
    var cursors by remember(viewer, capture, record, selected, repository) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember(viewer, capture, record, selected, repository) { mutableIntStateOf(0) }
    val result = visibleRead(viewer, capture, record, repository, selected, cursors.last(), refresh, intervalMs = 5000) { repository.segments(viewer, capture, record, selected, cursors.last()) }
    Column(Modifier.fillMaxSize()) {
        RecordPanelToolbar(listOf(RecordToolAction(stringResource(R.string.archives_back), onClick = back),
            RecordToolAction(stringResource(R.string.records_refresh)) { refresh++ }))
        Column(Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding)) {

            Text(stringResource(R.string.archives_timing), style = MaterialTheme.typography.bodySmall)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState({ refresh++ }, message = stringResource(R.string.archives_unavailable))
                else -> {
                    val page = result.getOrThrow()
                    Text(stringResource(archiveStatus(page.archiveStatus)), Modifier.padding(vertical = Dimens.SpaceS), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.archives_private_scope), style = MaterialTheme.typography.bodySmall)
                    if (page.archiveStatus == "incomplete") Text(stringResource(R.string.archives_incomplete_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        if (page.results.isEmpty()) item { WeMeetEmptyState(stringResource(R.string.archives_no_segments)) }
                        items(page.results, key = { it.id }) { segment ->
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                val from = if (segment.direction == "reverse") selected.configuration.targetLanguage else selected.configuration.sourceLanguage
                                Text(archiveLanguage(from) + " → " + archiveLanguage(segment.target), style = MaterialTheme.typography.labelMedium)
                                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                                Text(recordTime(segment.receivedAt), style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                            }
                        }
                        item { ArchivePageButtons(cursors, page.nextCursor) { cursors = it } }
                    }
                }
            }
        }
    }
}
