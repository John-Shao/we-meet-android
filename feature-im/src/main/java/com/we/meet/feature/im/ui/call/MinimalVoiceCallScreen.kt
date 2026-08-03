package com.we.meet.feature.im.ui.call

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.call.MeetInviteTracker
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImUserInfo
import com.we.meet.feature.im.ui.common.GroupAvatar
import kotlinx.coroutines.delay

/** One occupant of the multi-party voice grid (P4). LiveKit carries no photo,
 * so tiles render letter avatars from the display name. */
data class CallGridParticipant(
    val id: String,
    val name: String,
    val isLocal: Boolean,
    /** M2: active-speaker ring on the tile. */
    val speaking: Boolean = false,
)

/**
 * Feishu/WeCom-style minimal in-call screen for a **voice** call — avatar +
 * name + mm:ss duration, and a mic / hangup / speaker⇌earpiece control row.
 * Replaces the full meeting grid once a voice call connects.
 *
 * P4 escalation keeps voice on this screen: [upgraded] swaps the single-peer
 * center for a WeChat-group-voice style avatar grid of everyone in the room
 * plus still-ringing invitees (dimmed chips). Video escalation switches to the
 * full meeting UI at the RoomScreen level instead.
 *
 * Pure UI: the LiveKit room lives in the app module's RoomViewModel, so every
 * action is injected as a callback. Peer identity is resolved here from the IM
 * directory (same as [CallScreen]) — the LiveKit participant carries no photo.
 */
@Composable
fun MinimalVoiceCallScreen(
    deps: ImDeps,
    peerUid: String,
    fallbackName: String,
    connected: Boolean,
    micEnabled: Boolean,
    onToggleMic: () -> Unit,
    speakerOn: Boolean,
    onToggleSpeaker: () -> Unit,
    onHangup: () -> Unit,
    gridParticipants: List<CallGridParticipant> = emptyList(),
    /** M2: co-participants' ringing invites (label, state, avatarUrl) via data message. */
    remoteInvites: List<Triple<String, String, String?>> = emptyList(),
    onAddMember: (() -> Unit)? = null,
) {
    val session = remember(deps) { ImSession.get(deps) }
    LaunchedEffect(peerUid) { session.userDirectory.requestResolve(listOf(peerUid)) }
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val peer = remember(peerUid, directoryVersion) { session.userDirectory.get(peerUid) }
    val invites by session.meetInvites.invites.collectAsStateWithLifecycle()

    // Voice form follows the roster (2026-07-17 现状模型拍板): grid at ≥2
    // remotes or while invitees are still ringing; back to the 1:1 layout at
    // exactly one remote. Auto-end when alone lives in the host (needs leave).
    val remotes = gridParticipants.filter { !it.isLocal }
    val pendingCount = invites.count { !it.terminal }
    val grid = remotes.size >= 2 || pendingCount > 0 || remoteInvites.isNotEmpty()

    // Resolve room occupants (LiveKit identity = OIDC sub) to directory
    // profiles — the token name is a self-chosen join-preview name
    // (stale/synthetic-email, 实测问题2) and LiveKit carries no photo.
    var resolvedSubs by remember { mutableStateOf<Map<String, ImUserInfo>>(emptyMap()) }
    val gridIds = gridParticipants.map { it.id }
    LaunchedEffect(gridIds) {
        if (gridIds.isEmpty()) return@LaunchedEffect
        runCatching { session.bridge.resolveSubs(gridIds) }
            .onSuccess { resolvedSubs = resolvedSubs + it }
    }

    // 1:1 layout target: whoever is actually in the room (after a grid → 1:1
    // fallback that may be someone OTHER than the original peer); the invite
    // peer is the pre-connection fallback.
    val soloRemote = remotes.firstOrNull()
    val soloProf = soloRemote?.let { resolvedSubs[it.id] }
    val displayName = soloProf?.displayName?.takeIf { it.isNotBlank() }
        ?: soloRemote?.name?.takeIf { it.isNotBlank() && soloRemote.id != peerUid }
        ?: peer?.displayName?.takeIf { it.isNotBlank() }
        ?: fallbackName
    val displayAvatar = soloProf?.avatarUrl?.takeIf { it.isNotBlank() } ?: peer?.avatarUrl

    // Duration ticks from the moment the call connects. Anchored once via the
    // connected flag flipping true, so a recompose doesn't restart the clock.
    var connectedAt by remember { mutableLongStateOf(0L) }
    var elapsedSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
        while (true) {
            elapsedSec = (System.currentTimeMillis() - connectedAt) / 1000
            delay(1000)
        }
    }
    val status = if (connected) formatElapsed(elapsedSec)
    else stringResource(R.string.im_call_connecting)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (grid) {
            VoiceGrid(
                participants = gridParticipants,
                resolved = resolvedSubs,
                invites = invites,
                remoteInvites = remoteInvites,
                status = status,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = Dimens.SpaceXl),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = Dimens.SpaceXxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GroupAvatar(
                    tiles = listOf(
                        GroupTile(soloRemote?.id ?: peerUid, displayName, displayAvatar)
                    ),
                    size = Dimens.Chat.CallAvatarSize,
                )
                Spacer(Modifier.height(Dimens.SpaceXl))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Dimens.SpaceM))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = Dimens.Chat.CallControlsBottomInset,
                    start = Dimens.SpaceXl,
                    end = Dimens.SpaceXl,
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundButton(
                icon = if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = stringResource(
                    if (micEnabled) R.string.im_call_mic_on else R.string.im_call_mic_off
                ),
                background = if (micEnabled) NeutralControl else MutedControl,
                onClick = onToggleMic,
            )
            RoundButton(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.im_call_hangup),
                background = HangupRed,
                onClick = onHangup,
            )
            RoundButton(
                icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing,
                label = stringResource(
                    if (speakerOn) R.string.im_call_speaker else R.string.im_call_earpiece
                ),
                background = if (speakerOn) AcceptGreen else NeutralControl,
                onClick = onToggleSpeaker,
            )
            if (onAddMember != null) {
                RoundButton(
                    icon = Icons.Filled.PersonAdd,
                    label = stringResource(R.string.im_call_add_member),
                    background = NeutralControl,
                    onClick = onAddMember,
                )
            }
        }
    }
}

/**
 * P4 voice-meet grid: everyone in the room (letter avatars — LiveKit has no
 * photos) + still-pending invitees dimmed with a state line. 3 columns like
 * WeChat group voice; scrolls beyond 3 rows. Self is suffixed「(我)」.
 */
@Composable
private fun VoiceGrid(
    participants: List<CallGridParticipant>,
    resolved: Map<String, ImUserInfo>,
    invites: List<MeetInviteTracker.MeetInvite>,
    remoteInvites: List<Triple<String, String, String?>>,
    status: String,
    modifier: Modifier = Modifier,
) {
    val pending = invites.filter { !it.terminal }
    val failed = invites.filter {
        it.terminal && it.state != MeetInviteTracker.InviteState.ACCEPTED &&
            it.state != MeetInviteTracker.InviteState.CANCELED
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Dimens.Chat.SheetListMaxHeight),
        ) {
            items(participants, key = { "p:${it.id}" }) { p ->
                val prof = resolved[p.id]
                val name = prof?.displayName?.takeIf { it.isNotBlank() } ?: p.name
                GridCell(
                    id = p.id,
                    name = if (p.isLocal) {
                        stringResource(R.string.im_call_grid_self, name)
                    } else {
                        name
                    },
                    avatarUrl = prof?.avatarUrl?.takeIf { it.isNotBlank() },
                    speaking = p.speaking,
                )
            }
            items(pending, key = { "i:${it.callId}" }) { inv ->
                GridCell(
                    id = inv.userId,
                    name = inv.label,
                    avatarUrl = inv.avatarUrl,
                    dimmed = true,
                    stateText = stringResource(
                        if (inv.state == MeetInviteTracker.InviteState.RINGING) {
                            R.string.im_call_invite_ringing
                        } else {
                            R.string.im_call_invite_inviting
                        }
                    ),
                )
            }
            items(remoteInvites.size, key = { "r:$it:${remoteInvites[it].first}" }) { idx ->
                val (label, st, avatar) = remoteInvites[idx]
                GridCell(
                    id = "remote:$label",
                    name = label,
                    avatarUrl = avatar,
                    dimmed = true,
                    stateText = stringResource(
                        if (st == "ringing") {
                            R.string.im_call_invite_ringing
                        } else {
                            R.string.im_call_invite_inviting
                        }
                    ),
                )
            }
        }
        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.SpaceM))
            // map{} is inline so the stringResource call keeps its composable
            // context; joinToString's lambda wouldn't.
            val parts = failed.map { "${it.label}: ${stringResource(inviteStateRes(it.state))}" }
            Text(
                text = parts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Dimens.SpaceL))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (participants.count { !it.isLocal } == 0 && pending.isEmpty()) {
            Spacer(Modifier.height(Dimens.SpaceS))
            Text(
                text = stringResource(R.string.im_call_left_alone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GridCell(
    id: String,
    name: String,
    avatarUrl: String? = null,
    dimmed: Boolean = false,
    speaking: Boolean = false,
    stateText: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (dimmed) Modifier.alpha(0.45f) else Modifier,
    ) {
        Box(
            modifier = if (speaking) {
                Modifier.border(Dimens.BorderEmphasis, AcceptGreen, RoundedCornerShape(Dimens.CornerL))
            } else {
                Modifier
            },
        ) {
            GroupAvatar(tiles = listOf(GroupTile(id, name, avatarUrl)), size = Dimens.ActionTile)
        }
        Spacer(Modifier.height(Dimens.SpaceXs))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (stateText != null) {
            Text(
                text = stateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun inviteStateRes(state: MeetInviteTracker.InviteState): Int = when (state) {
    MeetInviteTracker.InviteState.REJECTED -> R.string.im_call_invite_rejected
    MeetInviteTracker.InviteState.BUSY -> R.string.im_call_invite_busy
    MeetInviteTracker.InviteState.UNREACHABLE -> R.string.im_call_invite_unreachable
    MeetInviteTracker.InviteState.TIMEOUT -> R.string.im_call_invite_timeout
    else -> R.string.im_call_invite_failed
}

/** Shared control button of the minimal call pages (voice + video). */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
    labelColor: Color? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = background,
            shape = RoundedCornerShape(Dimens.CornerL),
            modifier = Modifier.size(Dimens.IconIllustrationLarge),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(Dimens.Chat.CallControlIcon))
            }
        }
        Spacer(Modifier.height(Dimens.SpaceS))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatElapsed(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

/**
 * 通话配色的取值入口。
 *
 * 写成 @Composable 取值属性(而不是让每个调用处自己写
 * `WeMeetTheme.extras.im.callHangUp`)是为了让这两个通话页的几十处引用一行
 * 不用改 —— 这也正是 `MaterialTheme.colorScheme` 自己的写法。
 */
internal val HangupRed: Color
    @Composable get() = WeMeetTheme.extras.im.callHangUp
internal val AcceptGreen: Color
    @Composable get() = WeMeetTheme.extras.im.callAccept
internal val NeutralControl: Color
    @Composable get() = WeMeetTheme.extras.im.callNeutralControl
internal val MutedControl: Color
    @Composable get() = WeMeetTheme.extras.im.callMutedControl
