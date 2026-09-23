@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.ui.theme.Dimens

/**
 * 一条记录的「重命名 / 分享 / 协作者管理」菜单本体 —— 两处入口共用同一份。
 *
 * 入口:
 * - 实录详情页顶栏的三点按钮([RecordHeaderMenu] 渲染那颗按钮,再调用这里);
 * - 会议实录 / 智能纪要**列表项长按**(`RecordLibraryScreen` 直接调用这里)。
 *
 * 三个面板(分享底部弹层、协作者弹窗、重命名对话框)由本组件持有状态,所以调用方
 * 只给一个 `expanded` 和三个回调。长按版不渲染图标按钮 —— 菜单的锚点由调用方的
 * 布局决定,列表里长按哪一行,菜单就贴在哪一行旁边。
 *
 * [allowRename] 表示「这个上下文允不允许重命名」(智能纪要文档页传 false);
 * 记录自身的能力/来源/录制状态仍由 [canRenameRecord] 再判一次。
 */
@Composable
internal fun RecordMenuContent(
    app: WeMeetApp,
    viewer: String,
    record: RecordDto,
    objectScope: String,
    expanded: Boolean,
    allowRename: Boolean,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
    onChanged: () -> Unit,
    asPlayerSheet: Boolean = false,
    followState: TranscriptFollowState? = null,
    onSpeakers: (() -> Unit)? = null,
    onTranslations: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    val repository = app.meetingRecordRepository
    var panel by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (asPlayerSheet) {
        if (expanded) RecordPlaybackActionsSheet(
            title = record.title, owner = record.owner,
            onClose = onDismiss, followState = followState,
            onShare = { onDismiss(); copied = false; panel = "share" },
            onRename = if (allowRename && canRenameRecord(record)) ({ onDismiss(); renaming = true }) else null,
            onMembers = { onDismiss(); panel = "members" },
            onSpeakers = onSpeakers?.let { action -> { onDismiss(); action() } },
            onTranslations = onTranslations?.let { action -> { onDismiss(); action() } },
            onInfo = onInfo?.let { action -> { onDismiss(); action() } },
        )
    } else DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (allowRename && canRenameRecord(record)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.record_rename)) },
                onClick = { onDismiss(); renaming = true },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.collaboration_share)) },
            onClick = { onDismiss(); copied = false; panel = "share" },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.collaboration_manage)) },
            onClick = { onDismiss(); panel = "members" },
        )
    }
    if (renaming) {
        RecordRenameDialog(repository, viewer, record, onClose = { renaming = false }, onRenamed = onRenamed)
    }
    if (panel == "share") ModalBottomSheet(onDismissRequest = { panel = null }) {
        Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.collaboration_share), style = MaterialTheme.typography.titleLarge)
            Text(record.title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { panel = "chat" }, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) { Text(stringResource(R.string.collaboration_send)) }
            TextButton(onClick = {
                val url = RecordLinks.material(record.id, BuildConfig.WE_MEET_BASE_URL, objectScope)
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(record.title, url))
                copied = true
            }, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) { Text(stringResource(R.string.collaboration_copy)) }
            if (copied) Text(stringResource(R.string.collaboration_copied))
        }
    }
    if (panel == "chat") MaterialChatShare(app, viewer, record, objectScope) { panel = null }
    if (panel == "members") MaterialMembers(app, viewer, record, objectScope, onChanged) { panel = null }
}
