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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

/** Player entry point for existing record actions, with capability-gated optional rows. */
@Composable
internal fun RecordPlaybackActionsSheet(
    title: String,
    owner: String?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onMembers: () -> Unit,
    onRename: (() -> Unit)? = null,
    followState: TranscriptFollowState? = null,
    onSpeakers: (() -> Unit)? = null,
    onTranslations: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
) {
    val followLabel = stringResource(R.string.capture_playback_follow)
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
                    followState?.let {
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.MyLocation, null) },
                            headlineContent = { Text(followLabel) },
                            trailingContent = { Switch(checked = it.following, onCheckedChange = { _ -> it.toggle() },
                                modifier = Modifier.semantics { contentDescription = followLabel }) },
                        )
                    }
                    onInfo?.let { PlaybackAction(Icons.Outlined.Info, stringResource(R.string.records_info), it) }
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
