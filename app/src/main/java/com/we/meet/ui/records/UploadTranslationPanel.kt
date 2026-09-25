@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.UploadTranslationRequestDto
import com.we.meet.data.repository.UploadTranslationRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens
import java.util.UUID
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun UploadTranslationPanel(viewer: String, record: String, repository: UploadTranslationRepository,
    onExport: (String, String) -> Unit, onSource: ((Long) -> Unit)? = null) {
    var target by rememberSaveable(viewer, record) { mutableStateOf("en") }
    var refresh by remember(viewer, record) { mutableIntStateOf(0) }
    var intent by remember(viewer, record) { mutableStateOf<UploadTranslationRequestDto?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val listing = visibleRead(viewer, record, repository, refreshKey = refresh, intervalMs = 5000) { repository.list(viewer, record) }
    if (listing == null) { WeMeetInlineLoading(); return }
    if (listing.isFailure) { Column { RecordPanelToolbar(listOf(RecordToolAction(stringResource(R.string.records_refresh)) { refresh++ })); WeMeetInlineErrorState({ refresh++ }) }; return }
    val data = listing.getOrThrow()
    val selected = data.results.firstOrNull { it.target == target }
    val active = data.results.any { it.status in setOf("queued", "running") }
    val continuous = rememberRecordContinuousRead(viewer, record, selected?.id, selected?.inputRevision, selected?.status, repository,
        initial = 0, next = { it.nextPage },
        merge = { pages -> pages.last().copy(results = pages.flatMap { it.results }.distinctBy { it.segmentId }) },
        read = { page -> if (selected?.status == "succeeded") repository.detail(viewer, record, selected.id, page)
            else Result.failure(IllegalStateException("no translation")) })
    val detail = continuous.result
    val listState = rememberLazyListState()
    RecordAutoLoad(continuous, listState, disabled = saving)
    val translated = detail?.getOrNull().takeIf { selected?.status == "succeeded" }
    val stale = selected?.stale == true || translated?.stale == true
    var languagesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        RecordPanelToolbar(buildList {
            if (data.canGenerate && (intent != null || selected == null || selected.status != "succeeded" || stale)) {
                add(RecordToolAction(stringResource(if (intent != null) R.string.upload_translation_check else if (selected != null) R.string.upload_translation_regenerate else R.string.upload_translation_generate), !saving && (!active || intent != null)) {
                    if (!saving) {
                        val request = intent ?: UploadTranslationRequestDto(UUID.randomUUID().toString(), target, data.revision)
                        intent = request; saving = true; message = null
                        scope.launch {
                            try {
                                val result = repository.generate(viewer, record, request)
                                val error = result.exceptionOrNull()
                                if (result.isSuccess) { intent = null }
                                else if (error is HttpException && error.code() in setOf(400, 403, 404, 409, 429)) {
                                    intent = null; message = if (error.code() == 400) R.string.upload_translation_budget else R.string.upload_translation_conflict
                                } else message = R.string.upload_translation_uncertain
                                refresh++
                            } finally { saving = false }
                        }
                    }
                })
            }
            if (selected != null && translated != null && !stale) listOf("txt", "srt", "vtt").forEach { format ->
                add(RecordToolAction(stringResource(R.string.records_export_translation, format.uppercase())) { onExport(selected.id, format) })
            }
            add(RecordToolAction(stringResource(R.string.records_refresh), !saving && !continuous.busy) { refresh++; continuous.refresh() })
        }, showPrimaryWithLeading = data.canGenerate && (intent != null || selected == null || selected.status != "succeeded" || stale), leading = {
            TextButton(onClick = { languagesOpen = true }, enabled = !saving && intent == null, modifier = Modifier.weight(1f)) {
                Text(archiveLanguage(target), maxLines = 1)
                Icon(androidx.compose.material.icons.Icons.Outlined.ExpandMore, stringResource(R.string.records_translation_language))
            }
        })
        if (languagesOpen) ModalBottomSheet(onDismissRequest = { languagesOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding)) {
                Text(stringResource(R.string.records_translation_language), style = MaterialTheme.typography.titleMedium)
                listOf("en", "zh").forEach { language ->
                    TextButton(onClick = { target = language; message = null; languagesOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(archiveLanguage(language))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.upload_translation_description), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(stringResource(it)) }
            if (selected == null) { WeMeetEmptyState(stringResource(R.string.upload_translation_empty)); return@Column }
            Text(stringResource(uploadTranslationStatus(selected.status)))
            if (active) LinearProgressIndicator(progress = { selected.completedChunks.toFloat() / selected.totalChunks }, modifier = Modifier.fillMaxWidth())
            if (selected.stale) Text(stringResource(R.string.upload_translation_stale))
            if (selected.status == "succeeded") {
                when {
                    detail == null -> WeMeetInlineLoading()
                    detail.isFailure -> WeMeetInlineErrorState({ continuous.refresh() })
                    else -> {
                        val translated = requireNotNull(translated)
                        val stale = selected.stale || translated.stale
                        if (translated.stale && !selected.stale) Text(stringResource(R.string.upload_translation_stale))
                        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                            items(translated.results, key = { it.segmentId }) { row ->
                                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                    Text("${row.speakerName} · ${sourceTime(row.startMs)}", style = MaterialTheme.typography.labelMedium)
                                    if (onSource != null && !stale) TextButton(onClick = { onSource(row.startMs) }) { Text(stringResource(R.string.upload_translation_play)) }
                                    Text(stringResource(R.string.upload_translation_original, row.text), style = MaterialTheme.typography.bodySmall)
                                    Text(row.translatedText)
                                    HorizontalDivider()
                                }
                            }
                            item { RecordLoadMore(continuous, disabled = saving) }
                        }
                    }
                }
            }
        }
    }
}

private fun uploadTranslationStatus(status: String) = when (status) {
    "queued" -> R.string.upload_translation_queued
    "running" -> R.string.upload_translation_running
    "succeeded" -> R.string.upload_translation_succeeded
    "canceled" -> R.string.upload_translation_canceled
    else -> R.string.upload_translation_failed
}
