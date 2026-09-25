package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.TranslationArchiveDto
import com.we.meet.data.repository.TranslationArchiveRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
internal fun RecordTranslationArchives(viewer: String, record: String, repository: TranslationArchiveRepository) {
    var selection by remember(viewer, record, repository) { mutableStateOf<TranslationArchiveDto?>(null) }
    val selected = selection
    if (selected != null) {
        TranslationSegments(viewer, record, repository, selected) { selection = null }
        return
    }
    val continuous = rememberRecordContinuousRead(viewer, record, repository, initial = null as String?, next = { it.nextCursor },
        merge = { pages -> pages.last().copy(results = pages.flatMap { it.results }.distinctBy { it.id }) },
        read = { cursor -> repository.archives(viewer, record, cursor) })
    val result = continuous.result
    val listState = rememberLazyListState()
    RecordAutoLoad(continuous, listState)
    Column(Modifier.fillMaxSize()) {
        RecordPanelToolbar(listOf(RecordToolAction(stringResource(R.string.records_refresh)) { continuous.refresh() }))
        Column(Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding)) {
            Text(stringResource(R.string.archives_description), Modifier.padding(vertical = Dimens.SpaceS), style = MaterialTheme.typography.bodySmall)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState({ continuous.refresh() }, message = stringResource(R.string.archives_unavailable))
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    if (result.getOrThrow().results.isEmpty()) item { WeMeetEmptyState(stringResource(R.string.archives_empty)) }
                    items(result.getOrThrow().results, key = { it.id }) { archive ->
                        OutlinedCard(onClick = { selection = archive }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                Text(stringResource(if (archive.sourceKind == "private") R.string.archives_private else R.string.archives_shared), style = MaterialTheme.typography.titleSmall)
                                Text(archiveLanguage(archive.target) + " · " + stringResource(archiveStatus(archive.status)))
                                Text(recordTime(archive.createdAt), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.archives_count, archive.segmentCount), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { RecordLoadMore(continuous) }
                }
            }
        }
    }
}

@Composable
private fun TranslationSegments(viewer: String, record: String, repository: TranslationArchiveRepository, selected: TranslationArchiveDto, onBack: () -> Unit) {
    val continuous = rememberRecordContinuousRead(viewer, record, repository, selected, initial = null as String?, next = { it.nextCursor },
        merge = { pages -> pages.last().copy(results = pages.flatMap { it.results }.distinctBy { it.id }) },
        read = { cursor -> repository.segments(viewer, record, selected, cursor) })
    val result = continuous.result
    val listState = rememberLazyListState()
    RecordAutoLoad(continuous, listState)
    Column(Modifier.fillMaxSize()) {
        RecordPanelToolbar(listOf(RecordToolAction(stringResource(R.string.archives_back), onClick = onBack),
            RecordToolAction(stringResource(R.string.records_refresh)) { continuous.refresh() }))
        Column(Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding)) {

            Text(stringResource(R.string.archives_timing), style = MaterialTheme.typography.bodySmall)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState({ continuous.refresh() }, message = stringResource(R.string.archives_unavailable))
                else -> {
                    val page = result.getOrThrow()
                    Text(stringResource(archiveStatus(page.archiveStatus)), Modifier.padding(vertical = Dimens.SpaceS), style = MaterialTheme.typography.titleSmall)
                    if (page.sourceKind == "private") Text(stringResource(R.string.archives_private_scope), style = MaterialTheme.typography.bodySmall)
                    if (page.archiveStatus == "incomplete") Text(stringResource(R.string.archives_incomplete_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        if (page.results.isEmpty()) item { WeMeetEmptyState(stringResource(R.string.archives_no_segments)) }
                        items(page.results, key = { it.id }) { segment ->
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                Text(segment.speakerLabel.ifBlank { stringResource(R.string.interpretation_speaker) } + " · " + archiveLanguage(segment.target), style = MaterialTheme.typography.labelMedium)
                                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                                Text(recordTime(segment.receivedAt), style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider()
                            }
                        }
                        item { RecordLoadMore(continuous) }
                    }
                }
            }
        }
    }
}
@Composable internal fun archiveLanguage(value: String) = stringResource(if (value == "zh") R.string.archives_zh else R.string.archives_en)
internal fun archiveStatus(value: String) = when (value) {
    "capturing" -> R.string.archives_capturing
    "complete" -> R.string.archives_complete
    else -> R.string.archives_incomplete
}
