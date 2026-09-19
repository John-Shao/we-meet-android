package com.we.meet.ui.records

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.ui.home.ActionCard
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.api.RecordingUploadTicket
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import retrofit2.HttpException

/**
 * 「导入」动作的图标,与 Web 端同款:**向下**的箭头收进托盘
 * (Web `RecordingUpload.tsx` 用 Remix `RiDownload2Line`,字形等同 Material `save_alt`)。
 * Web 端明确否掉了向上的 upload 图标 —— 这一页的动作是「把外部的音视频收进来」,
 * 向上的箭头看着像要把东西发出去。
 *
 * 注意别和记录列表里「上传件」的来源徽标搞混:那个仍是向上的 `Icons.Outlined.UploadFile`,
 * 与 Web 端列表里的 `RiUpload2Line` 对应 —— 一个是动作,一个是来源类型。
 */
private val ImportIcon: ImageVector = Icons.Outlined.SaveAlt

/**
 * Above this, a single PUT means one break loses the whole transfer, so the
 * import switches to resumable parts. Mirrors the Web reader's threshold.
 */
private const val CHUNK_THRESHOLD = 100L * 1024 * 1024

/**
 * Advance a stream to a byte offset.
 *
 * `ContentResolver` streams do not reliably support `skip` for arbitrary
 * offsets — a document provider may return 0 without being at the end — so each
 * skip is verified and a shorter one is finished by reading.
 */
private fun skipFully(stream: java.io.InputStream, offset: Long) {
    var remaining = offset
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        // A provider that cannot skip still has to be read past.
        if (stream.read() < 0) throw java.io.IOException("File ended before the part offset")
        remaining -= 1
    }
}

/**
 * 「导入」入口的两种形态:AI 录音页的功能磁贴,以及记录/纪要页底栏那个描边按钮。
 * 能力表还没回来时也用它画灰态占位,尺寸与正常态一致,列表不会先塌一半再弹回来。
 */
@Composable
private fun ImportEntry(enabled: Boolean, onChoose: () -> Unit, modifier: Modifier, tile: Boolean) {
    if (tile) {
        ActionCard(ImportIcon, stringResource(R.string.records_upload),
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
            onChoose, modifier, enabled = enabled)
    } else {
        OutlinedButton(onClick = onChoose, enabled = enabled, modifier = modifier.heightIn(min = Dimens.MinTouchTarget), shape = CircleShape) {
            Icon(ImportIcon, null, Modifier.size(Dimens.ComponentIconMedium)); Spacer(Modifier.width(Dimens.SpaceS))
            Text(stringResource(R.string.records_upload))
        }
    }
}

@Composable
internal fun RecordingUploadAction(repository: RecordingUploadRepository, viewer: String, onRecord: (String) -> Unit, modifier: Modifier = Modifier, tile: Boolean = false) {
    // 入口先按上一次已知的能力表立刻画出来,再拉一次权威值纠正。能力表只说明允许的
    // 后缀与大小上限(不含任何记录内容),但「导入」入口画不画全靠它 —— 若每次都等
    // 一轮网络,每次进入「AI 录音」都会看到右侧空半格,入口迟到才冒出来。
    var config by remember(repository, viewer) { mutableStateOf(repository.lastCapabilities(viewer)) }
    var settled by remember(repository, viewer) { mutableStateOf(false) }
    LaunchedEffect(repository, viewer) {
        config = repository.capabilities(viewer).getOrNull()
        settled = true
    }
    val limits = config?.takeIf { it.available }
    if (limits == null) {
        // 首次进入、能力表还没回来:按同尺寸先摆一个不可点的入口,整行不会先塌一半;
        // 确认不可用或接口失败后就不再摆样子。
        if (!settled) ImportEntry(enabled = false, onChoose = {}, modifier = modifier, tile = tile)
        return
    }
    RecordingImportEntry(repository, viewer, onRecord, modifier, tile, limits)
}

/**
 * 能力表到手之后的正常流程。非空的能力表是前置条件:后缀白名单、大小上限,以及
 * 「系统文件选择器只看这些 MIME」都得读它。
 */
@Composable
private fun RecordingImportEntry(
    repository: RecordingUploadRepository,
    viewer: String,
    onRecord: (String) -> Unit,
    modifier: Modifier,
    tile: Boolean,
    config: RecordingUploadCapabilities,
) {
    var open by rememberSaveable(viewer) { mutableStateOf(false) }
    var uri by rememberSaveable(viewer) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(viewer) { mutableStateOf("") }
    var size by rememberSaveable(viewer) { mutableStateOf<Long?>(null) }
    var key by rememberSaveable(viewer) { mutableStateOf(UUID.randomUUID().toString()) }
    var context by rememberSaveable(viewer) { mutableStateOf("") }
    var hotwords by rememberSaveable(viewer) { mutableStateOf("") }
    var advanced by rememberSaveable(viewer) { mutableStateOf(false) }
    // An unanswered request may already have committed. Keep its key AND options for retries.
    var submitted by rememberSaveable(viewer) { mutableStateOf(false) }
    var uncertain by rememberSaveable(viewer) { mutableStateOf(false) }
    // A signed PUT for one exact object. Kept across retries so a failure after
    // the transfer re-declares those bytes instead of uploading them again.
    var ticket by remember(viewer) { mutableStateOf<RecordingUploadTicket?>(null) }
    // Resumable state: the server's session for this intent, how far it has got,
    // and whether the reader asked to stop.
    var sessionId by rememberSaveable(viewer) { mutableStateOf<String?>(null) }
    var uploadedBytes by remember(viewer) { mutableStateOf(0L) }
    var uploadedTotal by remember(viewer) { mutableStateOf(0L) }
    var cancelRequested by remember(viewer) { mutableStateOf(false) }
    var cancelled by remember(viewer) { mutableStateOf(false) }
    var busy by remember(viewer) { mutableStateOf(false) }
    var error by remember(viewer) { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val jobs = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) jobs.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { rows ->
                        check(rows.moveToFirst())
                        val length = rows.getColumnIndex(OpenableColumns.SIZE)
                        rows.getString(rows.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to
                            if (length >= 0 && !rows.isNull(length)) rows.getLong(length).takeIf { it >= 0 } else null
                    } ?: error("Missing document")
                }
                if (uri != selected.toString() || name != metadata.first || size != metadata.second) {
                    key = UUID.randomUUID().toString(); submitted = false; uncertain = false
                    // A ticket belongs to one file's bytes.
                    ticket = null
                }
                name = metadata.first; size = metadata.second; uri = selected.toString()
                error = false; open = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { uri = null; error = true; open = true }
        }
    }
    val valid = uri != null && name.substringAfterLast('.', "").lowercase() in config.extensions &&
        (size == null || size!! in 1..repository.maxBytes(config))
    val video = name.substringAfterLast('.', "").lowercase() in setOf("avi", "flv", "mkv", "mov", "mp4", "mpeg", "webm", "wmv")
    val mimeTypes = remember(config.extensions) { recordingImportMimeTypes(config.extensions) }
    val choose = { picker.launch(mimeTypes) }
    ImportEntry(enabled = !busy, onChoose = choose, modifier = modifier, tile = tile)
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false },
        title = { Text(stringResource(R.string.record_upload_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Text(stringResource(R.string.record_upload_hint, repository.maxBytes(config) / 1024 / 1024))
                OutlinedButton(onClick = choose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(name.ifBlank { stringResource(R.string.record_upload_choose) })
                }
                Text(stringResource(if (video) R.string.record_import_video else R.string.record_import_audio))
                Text(size?.let { android.text.format.Formatter.formatFileSize(LocalContext.current, it) }
                    ?: stringResource(R.string.record_import_size_unknown))
                if (video) Text(stringResource(R.string.record_import_video_hint))
                if (uri != null && !valid) Text(stringResource(R.string.record_import_invalid), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.record_upload_advanced)) }
                if (advanced) {
                    OutlinedTextField(context, onValueChange = { context = it.take(400) }, enabled = !busy && !submitted,
                        label = { Text(stringResource(R.string.record_upload_context)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(hotwords, onValueChange = { hotwords = it.take(4000) }, enabled = !busy && !submitted,
                        label = { Text(stringResource(R.string.record_upload_hotwords)) }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.record_upload_consent), style = MaterialTheme.typography.bodySmall)
                if (busy) {
                    if (uploadedTotal > 0) {
                        Text(
                            stringResource(
                                R.string.record_upload_progress,
                                ((uploadedBytes * 100) / uploadedTotal).toInt(),
                            )
                        )
                        LinearProgressIndicator(
                            progress = { uploadedBytes.toFloat() / uploadedTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.record_upload_wait))
                }
                if (busy) {
                    TextButton(onClick = { cancelRequested = true }) {
                        Text(stringResource(R.string.record_upload_cancel))
                    }
                }
                if (cancelled && !busy) {
                    Text(stringResource(R.string.record_upload_cancelled))
                }
                if (uncertain && !busy) Text(stringResource(R.string.record_upload_unconfirmed), color = MaterialTheme.colorScheme.error)
                else if (error) Text(stringResource(R.string.record_upload_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                val document = Uri.parse(uri ?: return@TextButton)
                if (busy) return@TextButton
                busy = true; error = false; submitted = true
                cancelRequested = false; cancelled = false
                uploadedBytes = 0; uploadedTotal = 0
                jobs.launch {
                    try {
                        val bytes = {
                            resolver.openInputStream(document) ?: error("Document unavailable")
                        }
                        // A file too large for multipart can only travel by the
                        // presigned path, and that path needs its byte count.
                        val direct = repository.needsDirectUpload(size, config)
                        // Past the threshold one PUT means a break loses the whole
                        // transfer, so large files go up in resumable parts.
                        val chunked = direct && (size ?: 0) > CHUNK_THRESHOLD
                        val result = if (chunked) {
                            repository.uploadChunked(
                                com.we.meet.data.repository.ChunkedUploadRequest(
                                    viewer = viewer,
                                    key = key,
                                    name = name,
                                    size = size!!,
                                    config = config,
                                    context = context,
                                    hotwords = hotwords,
                                    contentType = contentTypeFor(name, resolver.getType(document)),
                                    // A remembered session is a hint; the server
                                    // still decides which parts already exist.
                                    resumeFrom = sessionId,
                                    openAt = { offset, length ->
                                        requireNotNull(
                                            resolver.openInputStream(document)
                                        ).also { skipFully(it, offset) }
                                    },
                                    onProgress = { sent, total ->
                                        uploadedBytes = sent
                                        uploadedTotal = total
                                    },
                                    cancelled = { cancelRequested },
                                )
                            ).also { outcome ->
                                if (outcome.isSuccess) sessionId = null
                            }

                        } else if (direct) {
                            repository.uploadDirect(
                                viewer, key, name, size!!, config, context, hotwords, bytes,
                                ticket, contentTypeFor(name, resolver.getType(document)),
                            )
                        } else {
                            repository.upload(viewer, key, name, size, config, context, hotwords, bytes)
                        }
                        if (result.isSuccess) {
                            open = false; ticket = null; uri = null; name = ""; submitted = false; uncertain = false
                            onRecord(result.getOrThrow().recordId)
                        } else {
                            // A spent ticket cannot be reused; the next attempt asks
                            // for a fresh signature instead of retrying a dead URL.
                            if (result.exceptionOrNull() is java.io.IOException) ticket = null
                            val failure = result.exceptionOrNull()
                            // Only a definite rejection permits changing the original options.
                            // A 409 on a retry can still refer to a previously accepted intent.
                            val rejected = failure is IllegalArgumentException ||
                                (failure is HttpException && failure.code() in 400..499 && failure.code() !in setOf(408, 409))
                            if (!uncertain && rejected) submitted = false
                            uncertain = uncertain || !rejected
                            error = true
                        }
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.record_upload_submit)) }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = { open = false }) { Text(stringResource(R.string.records_close)) }
        })
}

@Composable
internal fun RecordingUploadStatus(repository: RecordingUploadRepository, viewer: String, recordId: String) {
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    var busy by remember(viewer, recordId) { mutableStateOf(false) }
    var error by remember(viewer, recordId) { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    val state = visibleRead(repository, viewer, recordId, refresh, intervalMs = 5_000,
        stopWhen = { it.status == "succeeded" || it.status == "failed" }) { repository.state(viewer, recordId) }
    val result = state?.getOrNull()
    if (result?.status == "succeeded") return
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        when {
            state == null -> WeMeetInlineLoading()
            state.isFailure -> Text(stringResource(R.string.records_unavailable))
            else -> Text(stringResource(when (result?.status) {
                "failed" -> R.string.record_upload_failed
                "queued" -> R.string.record_upload_queued
                else -> R.string.record_upload_transcribing
            }))
        }
        if (result?.status == "failed" && result.retryable) TextButton(enabled = !busy, onClick = {
            busy = true; error = false
            jobs.launch { try { error = repository.retry(viewer, recordId, result.attempt).isFailure; refresh++ } finally { busy = false } }
        }) { Text(stringResource(R.string.record_upload_retry)) }
        if (state?.isFailure == true) TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
        if (error) Text(stringResource(R.string.record_upload_error), color = MaterialTheme.colorScheme.error)
    }
}

/** Android document providers filter by MIME; the selected extension is also validated. */
internal fun recordingImportMimeTypes(extensions: List<String>): Array<String> {
    val types = mapOf(
        "aac" to listOf("audio/aac", "audio/x-aac"), "amr" to listOf("audio/amr"),
        "aiff" to listOf("audio/aiff", "audio/x-aiff"), "avi" to listOf("video/x-msvideo"),
        "flac" to listOf("audio/flac", "audio/x-flac"), "flv" to listOf("video/x-flv"),
        "m4a" to listOf("audio/mp4", "audio/x-m4a"), "mkv" to listOf("video/x-matroska", "audio/x-matroska"),
        "mov" to listOf("video/quicktime"), "mp3" to listOf("audio/mpeg"),
        "mp4" to listOf("video/mp4", "audio/mp4"), "mpeg" to listOf("video/mpeg"),
        "ogg" to listOf("audio/ogg", "video/ogg", "application/ogg"), "opus" to listOf("audio/opus", "audio/ogg", "application/ogg"),
        "wav" to listOf("audio/wav", "audio/x-wav"), "webm" to listOf("video/webm", "audio/webm"),
        "wma" to listOf("audio/x-ms-wma"), "wmv" to listOf("video/x-ms-wmv")
    )
    return extensions.flatMap { types[it.lowercase()].orEmpty() }.distinct()
        .ifEmpty { listOf("audio/*", "video/*") }.toTypedArray()
}

/**
 * The type signed into the presigned PUT.
 *
 * The provider's own answer wins when it has one, because that is what the bytes
 * actually are; the extension mapping is the fallback for providers that report
 * `application/octet-stream`.
 */
internal fun contentTypeFor(name: String, providerType: String?): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    val mapped = recordingImportMimeTypes(listOf(extension)).firstOrNull()
    return providerType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: mapped
        ?: "application/octet-stream"
}
