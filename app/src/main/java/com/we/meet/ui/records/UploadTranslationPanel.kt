package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
    var target by remember(viewer, record) { mutableStateOf("en") }
    var page by remember(viewer, record, target) { mutableIntStateOf(0) }
    var refresh by remember(viewer, record) { mutableIntStateOf(0) }
    var intent by remember(viewer, record) { mutableStateOf<UploadTranslationRequestDto?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val listing = visibleRead(viewer, record, repository, refresh, intervalMs = 5000) { repository.list(viewer, record) }
    if (listing == null) { WeMeetInlineLoading(); return }
    if (listing.isFailure) { WeMeetInlineErrorState({ refresh++ }); return }
    val data = listing.getOrThrow()
    val selected = data.results.firstOrNull { it.target == target }
    val active = data.results.any { it.status in setOf("queued", "running") }
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.upload_translation_description), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            listOf("en", "zh").forEach { language ->
                FilterChip(selected = target == language, enabled = !saving && intent == null, onClick = { target = language; message = null }, label = { Text(archiveLanguage(language)) })
            }
        }
        if (data.canGenerate && (intent != null || selected == null || selected.status != "succeeded" || selected.stale)) {
            Button(enabled = !saving && (!active || intent != null), onClick = {
                if (!saving) {
                    val request = intent ?: UploadTranslationRequestDto(UUID.randomUUID().toString(), target, data.revision)
                    intent = request; saving = true; message = null
                    scope.launch {
                        try {
                            val result = repository.generate(viewer, record, request)
                            val error = result.exceptionOrNull()
                            if (result.isSuccess) { intent = null; page = 0 }
                            else if (error is HttpException && error.code() in setOf(400, 403, 404, 409, 429)) {
                                intent = null; message = if (error.code() == 400) R.string.upload_translation_budget else R.string.upload_translation_conflict
                            } else message = R.string.upload_translation_uncertain
                            refresh++
                        } finally { saving = false }
                    }
                }
            }) { Text(stringResource(if (intent != null) R.string.upload_translation_check else if (selected != null) R.string.upload_translation_regenerate else R.string.upload_translation_generate)) }
        }
        message?.let { Text(stringResource(it)) }
        if (selected == null) { WeMeetEmptyState(stringResource(R.string.upload_translation_empty)); return@Column }
        Text(stringResource(uploadTranslationStatus(selected.status)))
        if (active) LinearProgressIndicator(progress = { selected.completedChunks.toFloat() / selected.totalChunks }, modifier = Modifier.fillMaxWidth())
        if (selected.stale) Text(stringResource(R.string.upload_translation_stale))
        if (selected.status == "succeeded") {
            val detail = visibleRead(viewer, record, selected.id, page, refresh) { repository.detail(viewer, record, selected.id, page) }
            when {
                detail == null -> WeMeetInlineLoading()
                detail.isFailure -> WeMeetInlineErrorState({ refresh++ })
                else -> {
                    val translated = detail.getOrThrow()
                    val stale = selected.stale || translated.stale
                    if (translated.stale && !selected.stale) Text(stringResource(R.string.upload_translation_stale))
                    if (!stale) Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        listOf("txt", "srt", "vtt").forEach { format ->
                            TextButton(onClick = { onExport(selected.id, format) }) { Text(format.uppercase()) }
                        }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        items(translated.results, key = { it.segmentId }) { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                                Text("${row.speakerName} · ${sourceTime(row.startMs)}", style = MaterialTheme.typography.labelMedium)
                                if (onSource != null && !stale) TextButton(onClick = { onSource(row.startMs) }) { Text(stringResource(R.string.upload_translation_play)) }
                                Text(stringResource(R.string.upload_translation_original, row.text), style = MaterialTheme.typography.bodySmall)
                                Text(row.translatedText)
                                HorizontalDivider()
                            }
                        }
                        item {
                            Row {
                                if (page > 0) TextButton(onClick = { page-- }) { Text(stringResource(R.string.records_previous)) }
                                translated.nextPage?.let { next -> TextButton(onClick = { page = next }) { Text(stringResource(R.string.records_next)) } }
                            }
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
