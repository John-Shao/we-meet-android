package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.we.meet.R
import com.we.meet.data.api.dto.RecordLifecycleDto
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun RecordPurgeConfirmation(viewer: String, item: RecordLifecycleDto, repository: MeetingRecordRepository, onClose: () -> Unit) {
    var accepted by remember { mutableStateOf(item.purge != null) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val revision = item.purge?.expectedRevision ?: item.lifecycleRevision
    val scope = rememberCoroutineScope()
    val status = if (accepted) visibleRead(viewer, item.id, refresh, intervalMs = 5_000, stopWhen = { it.state == "complete" }) { repository.purgeStatus(viewer, item.id, revision) } else null
    val receipt = status?.getOrNull()
    val denied = status?.isFailure == true
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.record_purge_remove)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            if (denied) {
                Text(stringResource(R.string.record_trash_unavailable), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.waiting_retry)) }
            } else {
                Text(item.title)
                if (!accepted) {
                    Text(stringResource(R.string.record_purge_hint))
                    Row(Modifier.toggleable(value = acknowledged, enabled = !busy, role = Role.Checkbox, onValueChange = { acknowledged = it })) { Checkbox(checked = acknowledged, onCheckedChange = null, enabled = !busy); Text(stringResource(R.string.record_purge_acknowledge)) }
                } else if (receipt == null) WeMeetInlineLoading()
                else {
                    Text(stringResource(when (receipt.state) { "complete" -> R.string.record_purge_complete; "failed" -> R.string.record_purge_failed; else -> R.string.record_purge_pending }))
                    if (receipt.state == "pending") Text(stringResource(R.string.record_purge_wait, recordTime(receipt.notBefore)))
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (busy) WeMeetInlineLoading()
            }
        }
    }, confirmButton = {
        // 永久删除是不可逆的 —— 确认键必须是危险色(设计规范 §7)。
        if (!denied && (!accepted || receipt?.canRetry == true)) TextButton(
            enabled = !busy && error != R.string.record_trash_conflict && (accepted || acknowledged),
            colors = ButtonDefaults.textButtonColors(contentColor = WeMeetTheme.extras.status.danger),
            onClick = {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    val result = repository.purge(viewer, item.id, revision)
                    busy = false
                    result.fold(onSuccess = { accepted = true; refresh++ }, onFailure = {
                        error = if ((it as? HttpException)?.code() in listOf(400, 401, 403, 404, 409)) R.string.record_trash_conflict else R.string.record_purge_uncertain
                    })
                }
            }
        }) { Text(stringResource(if (accepted) R.string.record_purge_retry else R.string.record_purge_confirm)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.record_purge_close)) } })
}
