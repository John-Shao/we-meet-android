package com.we.meet.feature.im.ui.chat

import com.we.meet.feature.im.ui.common.ImSwitchRow
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.ui.common.ErrorBanner
import com.we.meet.feature.im.vm.DirectChatSettingsViewModel

/**
 * Direct (1-on-1) chat settings — mirrors Web DirectSettingsPanel.
 *
 * Surfaces: peer identity, "create group" seeded with this peer, pin / mute
 * toggles, clear history. Full-screen route pushed from ChatScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectChatSettingsScreen(
    deps: ImDeps,
    cid: String,
    onBack: () -> Unit,
    /** Open the group picker seeded with this direct chat's peer (userId, 可空)。 */
    onCreateGroup: (peerUserId: String?) -> Unit,
    /** P8 日程：双方忙闲对比页（app 层接 FREE_BUSY 路由）。resolve 未完成
     * (peerUserId null,如跨组织)时该行置灰。null 隐藏入口。 */
    onViewCalendar: ((peerUserId: String, peerName: String) -> Unit)? = null,
) {
    val vm: DirectChatSettingsViewModel =
        viewModel(key = "direct-settings-$cid", factory = remember(deps, cid) {
            DirectChatSettingsViewModel.Factory(deps, cid)
        })
    val ui by vm.ui.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.im_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ui.error?.let { ErrorBanner(stringResource(it)) }

            // Peer identity: avatar + name.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.ScreenPadding),
            ) {
                MemberAvatar(
                    name = ui.peerName,
                    url = ui.peerAvatarUrl,
                    cacheKey = "im-avatar:${ui.peerUserId ?: cid}",
                    size = Dimens.ListLeadingIcon,
                )
                Text(
                    text = ui.peerName.ifBlank { cid.take(8) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = Dimens.SpaceM),
                )
            }
            HorizontalDivider()

            // Create group seeded with this peer.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !ui.busy) { onCreateGroup(ui.peerUserId) }
                    .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
            ) {
                Text(
                    text = stringResource(R.string.im_group_button),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()

            // Conversation apps use the same hierarchy in direct and group settings.
            if (onViewCalendar != null) {
                val peerId = ui.peerUserId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                ) {
                    Text(
                        text = stringResource(R.string.im_group_apps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(modifier = Modifier.padding(top = Dimens.SpaceS)) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dimens.CornerS))
                                .clickable(enabled = peerId != null) {
                                    peerId?.let { onViewCalendar(it, ui.peerName) }
                                }
                                .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(Dimens.ListLeadingIcon)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(Dimens.CornerS),
                                    ),
                            ) {
                                Icon(
                                    Icons.Filled.CalendarMonth,
                                    contentDescription = null,
                                    tint = if (peerId != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stringResource(R.string.im_view_calendar),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peerId != null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Dimens.SpaceXs),
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            // Pin toggle.
            ImSwitchRow(
                label = stringResource(R.string.im_menu_pin),
                checked = ui.pinned,
                onToggle = { vm.togglePin() },
            )
            // Mute toggle.
            ImSwitchRow(
                label = stringResource(R.string.im_menu_mute),
                checked = ui.muted,
                onToggle = { vm.toggleMute() },
            )

            HorizontalDivider()
            Spacer(Modifier.height(Dimens.SpaceM))

            // Clear history.
            Text(
                text = stringResource(R.string.im_group_clear_history),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { confirmClear = true }
                    .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.im_group_clear_history)) },
            text = { Text(stringResource(R.string.im_group_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.im_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.im_action_cancel))
                }
            },
        )
    }
}
