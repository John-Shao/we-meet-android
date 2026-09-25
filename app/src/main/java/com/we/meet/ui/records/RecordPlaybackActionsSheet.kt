@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

/** Record actions opened from the page header, with capability-gated optional rows. */
@Composable
internal fun RecordPlaybackActionsSheet(
    title: String,
    owner: String?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onMembers: () -> Unit,
    onRename: (() -> Unit)? = null,
    onSpeakers: (() -> Unit)? = null,
    onTranslations: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Headphones, null, Modifier.size(Dimens.IconXl), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Dimens.SpaceM))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    owner?.takeIf { it.isNotBlank() }?.let {
                        Text("${stringResource(R.string.records_info_owner)} $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_records_close)) }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column {
                    PlaybackAction(Icons.Outlined.Share, stringResource(R.string.collaboration_share), onShare)
                    onRename?.let { PlaybackAction(Icons.Outlined.Edit, stringResource(R.string.record_rename), it) }
                    PlaybackAction(Icons.Outlined.Group, stringResource(R.string.collaboration_manage), onMembers)
                    onSpeakers?.let { PlaybackAction(Icons.Outlined.RecordVoiceOver, stringResource(R.string.records_speakers), it) }
                    onTranslations?.let { PlaybackAction(Icons.Outlined.Translate, stringResource(R.string.archives_title), it) }
                    onInfo?.let { PlaybackAction(Icons.Outlined.Info, stringResource(R.string.records_info), it) }
                    onTrash?.let { action ->
                        HorizontalDivider()
                        TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Spacer(Modifier.width(Dimens.SpaceS))
                            Text(stringResource(R.string.record_trash_remove))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        ListItem(leadingContent = { Icon(icon, null) }, headlineContent = { Text(label) },
            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget))
    }
}
