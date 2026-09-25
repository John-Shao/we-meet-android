@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.api.dto.RecordLifecycleDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.DangerButton
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun RecordTrashControl(viewer: String, record: RecordDto, repository: MeetingRecordRepository, menu: Boolean = false, onRemoved: () -> Unit) {
    if (!record.capabilities.trash || record.lifecycleRevision == null) return
    key(viewer, record.id) {
        var selected by remember { mutableStateOf<RecordLifecycleDto?>(null) }
        val choose = { selected = RecordLifecycleDto(record.id, record.title, record.sourceType, null, record.lifecycleRevision) }
        if (menu) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert, stringResource(R.string.records_page_actions))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.record_trash_remove), color = MaterialTheme.colorScheme.error) },
                        onClick = { expanded = false; choose() })
                }
            }
        } else DangerButton(text = stringResource(R.string.record_trash_remove), onClick = choose)
        selected?.let { item -> RecordLifecycleConfirmation(viewer, item, repository, "trashed", { selected = null }) { selected = null; onRemoved() } }
    }
}

@Composable
internal fun RecordLifecycleConfirmation(viewer: String, item: RecordLifecycleDto, repository: MeetingRecordRepository, target: String, onClose: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(if (target == "trashed") R.string.record_trash_remove else R.string.record_trash_restore)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(item.title)
            Text(stringResource(if (target == "trashed") R.string.record_trash_remove_hint else R.string.record_trash_restore_hint))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (busy) WeMeetInlineLoading()
        }
    }, confirmButton = {
        TextButton(enabled = !busy && error != R.string.record_trash_conflict, 
            // 「移入回收站」用危险色;「恢复」不是破坏性动作,保持中性 —— 同一个弹窗
            // 承载两个方向的相反动作,颜色不能一视同仁。
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (target == "trashed") WeMeetTheme.extras.status.danger else LocalContentColor.current,
            ),
            onClick = {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    val result = repository.lifecycle(viewer, item.id, target, item.lifecycleRevision)
                    busy = false
                    result.fold(onSuccess = { onDone() }, onFailure = {
                        error = if ((it as? HttpException)?.code() in listOf(400, 401, 403, 404, 409)) R.string.record_trash_conflict else R.string.record_trash_uncertain
                    })
                }
            }
        }) { Text(stringResource(if (target == "trashed") R.string.record_trash_confirm_remove else R.string.record_trash_confirm_restore)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.record_trash_cancel)) } })
}

@Composable
internal fun RecordTrashSheet(viewer: String, repository: MeetingRecordRepository, onClose: () -> Unit) {
    var cursors by remember(viewer) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember(viewer) { mutableStateOf<RecordLifecycleDto?>(null) }
    var purging by remember(viewer) { mutableStateOf<RecordLifecycleDto?>(null) }
    val page = visibleRead(viewer, cursors.last(), refresh) { repository.trash(viewer, cursors.last()) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.record_trash_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (page?.getOrNull()?.purgeAvailable == true) R.string.record_purge_retention_hint else R.string.record_trash_retention_hint), style = MaterialTheme.typography.bodySmall)
            when {
                page == null -> WeMeetInlineLoading()
                page.isFailure -> WeMeetInlineErrorState(onRetry = { selected = null; refresh++ }, message = stringResource(R.string.record_trash_unavailable))
                else -> {
                    val result = page.getOrThrow()
                    if (result.results.isEmpty()) WeMeetInlineEmptyState(stringResource(R.string.record_trash_empty))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        items(result.results, key = { it.id }) { row ->
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            row.deletedAt?.let { Text(recordTime(it), style = MaterialTheme.typography.bodySmall) }
                            if (row.purge == null) TextButton(onClick = { selected = row }) { Text(stringResource(R.string.record_trash_restore)) }
                            // 永久删除与「恢复」上下相邻:必须是危险色,否则两颗同色同重,
                            // 用户分不出哪一颗是不可逆的。
                            if (row.purge != null || result.purgeAvailable) TextButton(
                                onClick = { purging = row },
                                colors = ButtonDefaults.textButtonColors(contentColor = WeMeetTheme.extras.status.danger),
                            ) { Text(stringResource(if (row.purge != null) R.string.record_purge_status else R.string.record_purge_remove)) }
                        }
                    }
                    RecordPager(
                        hasPrevious = cursors.size > 1,
                        onPrevious = { cursors = cursors.dropLast(1) },
                        hasNext = result.nextCursor != null,
                        onNext = { result.nextCursor?.let { cursor -> cursors = cursors + cursor } },
                    )
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.records_close)) }
        }
    }
    if (page?.isSuccess == true) selected?.let { item ->
        key(viewer, item.id) {
            RecordLifecycleConfirmation(viewer, item, repository, "active", { selected = null; refresh++ }) { selected = null; cursors = listOf(null); refresh++ }
        }
    }
    if (page?.isSuccess == true) purging?.let { item ->
        key(viewer, item.id) { RecordPurgeConfirmation(viewer, item, repository) { purging = null; cursors = listOf(null); refresh++ } }
    }
}
