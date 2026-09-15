@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.service.CaptureServiceState
import java.util.Locale

/**
 * 顶栏工具按钮：形态与「会议实录」等页面的顶栏 actions 一致 —— 纯图标 [IconButton]，
 * 不再在图标下占一行可见文字。文字只作为 TalkBack 名称保留（[IconButton] 默认
 * 48dp 热区，见设计规范 §5.2）。
 * 开启态改用 `primary` 着色表达，与会议实录的筛选按钮同一套写法。
 */
@Composable
internal fun CaptureTool(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    IconButton(onClick) {
        Icon(icon, contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
internal fun CaptureDocumentTabs(summary: Boolean, select: (Boolean) -> Unit, saved: Boolean = false) {
    TabRow(selectedTabIndex = if (summary) 1 else 0, containerColor = MaterialTheme.colorScheme.surface) {
        Tab(selected = !summary, onClick = { select(false) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Text(stringResource(R.string.capture_asr_title)) })
        Tab(selected = summary, onClick = { select(true) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = {
            Text(stringResource(if (saved) R.string.records_minutes else R.string.capture_live_summary))
        })
    }
}

@Composable
internal fun CaptureTranscriptEntry(time: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(Dimens.IconTiny),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun CaptureDocumentEmpty(summary: Boolean, preparing: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceXxxl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Icon(if (summary) Icons.Outlined.AutoAwesome else Icons.Outlined.GraphicEq, contentDescription = null,
                modifier = Modifier.padding(Dimens.SpaceL).size(Dimens.IconXl), tint = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(if (summary) R.string.capture_summary_empty_title else R.string.capture_document_empty_title),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (preparing) R.string.capture_document_preparing else if (summary) R.string.capture_summary_empty_hint else R.string.capture_document_empty_hint),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun CaptureNotice(message: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(Dimens.CornerM)) {
        Column(Modifier.fillMaxWidth().padding(Dimens.SpaceM)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Time is acknowledged capture duration; no synthetic waveform or inferred microphone level. */
@Composable
internal fun CaptureRecordingDock(state: CaptureServiceState, status: Int, canStart: Boolean, ending: Boolean,
    onStart: () -> Unit, onPause: () -> Unit, onFinish: () -> Unit) {
    val local = state.local
    val seconds = (local?.durationMs ?: 0L) / 1000
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = Dimens.ElevationSubtle, shadowElevation = Dimens.ElevationOverlay) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Box(Modifier.size(Dimens.SpaceS).background(if (state.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, CircleShape))
                Text(stringResource(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.busy) CircularProgressIndicator(Modifier.size(Dimens.IconTiny), strokeWidth = Dimens.ProgressStroke)
            }
            Text(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
                style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                if (!ending) Button(onClick = if (state.recording) onPause else onStart,
                    enabled = if (state.recording) !state.busy else canStart,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.ButtonHeight), shape = RoundedCornerShape(Dimens.CornerL)) {
                    Icon(if (state.recording) Icons.Outlined.Pause else Icons.Outlined.Mic, null, Modifier.size(Dimens.ComponentIconMedium))
                    Spacer(Modifier.width(Dimens.SpaceS))
                    Text(stringResource(if (state.recording) R.string.capture_pause else if (local == null || local.sealed) R.string.capture_start else R.string.capture_resume))
                }
                if (local != null && !local.sealed) OutlinedButton(onClick = onFinish, enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.ButtonHeight), shape = RoundedCornerShape(Dimens.CornerL)) {
                    Icon(Icons.Outlined.Stop, null, Modifier.size(Dimens.ComponentIconMedium))
                    Spacer(Modifier.width(Dimens.SpaceS))
                    Text(stringResource(if (ending) R.string.capture_complete_save else R.string.capture_finish))
                }
            }
        }
    }
}

@Composable
internal fun CaptureAudioSettings(textOnly: Boolean, editable: Boolean, textAvailable: Boolean,
    change: (Boolean) -> Unit, onDismiss: () -> Unit) {
    CaptureSettingsSheet(stringResource(R.string.capture_audio_settings), onDismiss) {
        Text(stringResource(if (editable) R.string.capture_audio_settings_hint else R.string.capture_audio_settings_locked),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            CaptureSettingOption(stringResource(R.string.capture_keep_audio), stringResource(R.string.capture_keep_audio_hint),
                !textOnly, editable) { change(false) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CaptureSettingOption(stringResource(R.string.capture_text_only), stringResource(R.string.capture_text_only_hint),
                textOnly, editable && textAvailable) { change(true) }
        }
        if (!textAvailable && editable) Text(stringResource(R.string.capture_text_unavailable), style = MaterialTheme.typography.bodySmall)
        if (textOnly) Text(stringResource(R.string.capture_text_consent), style = MaterialTheme.typography.bodySmall)
        Button(onDismiss, Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget)) { Text(stringResource(R.string.capture_settings_done)) }
    }
}

@Composable
private fun CaptureSettingOption(title: String, hint: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = Dimens.SpaceL), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = if (enabled || selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

@Composable
internal fun CaptureSettingsSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding)
            .padding(bottom = Dimens.SpaceXl), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.records_close)) }
            }
            content()
        }
    }
}
