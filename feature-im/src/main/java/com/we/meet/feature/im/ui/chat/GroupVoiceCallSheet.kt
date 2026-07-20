package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.call.MeetInviteTracker
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.data.ImUserInfo
import com.we.meet.feature.im.ui.common.GroupAvatar

/**
 * P4.1 群语音通话 — group-member multi-picker (roster-scoped, unlike the
 * directory-scoped ContactPicker). Everyone is pre-selected (拍板: 默认全选);
 * self is excluded; members that don't resolve in the caller's org are hidden
 * (cross-org data boundary). Confirm hands back MeetInviteTracker.Target list
 * — the resolved `.id` IS the we-meet userId the tracker needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupVoiceCallSheet(
    session: ImSession,
    cid: String,
    memberUids: List<String>,
    /** P5.1: heading override — the group video-meeting entry reuses this
     * sheet (defaults to the group-voice-call title). */
    title: String? = null,
    /** P5: `allMembers` = the full resolved roster regardless of check state —
     * the caller reports it as the room's suggested invitees (Feishu 场景2:
     * 群发起会的建议参会 = 全体群成员)。 */
    onCall: (List<MeetInviteTracker.Target>, allMembers: List<MeetInviteTracker.Target>) -> Unit,
    onDismiss: () -> Unit,
) {
    var resolved by remember { mutableStateOf<Map<String, ImUserInfo>>(emptyMap()) }
    // 群昵称(P10):uid → 非空昵称,覆盖目录显示名,与群成员列表口径一致。
    var nicknames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(memberUids, cid) {
        if (memberUids.isEmpty()) return@LaunchedEffect
        runCatching { session.bridge.resolveUsers(memberUids) }
            .onSuccess { resolved = it }
        runCatching { session.client.listMembers(cid) }
            .onSuccess { roster ->
                nicknames = roster.mapNotNull { m ->
                    m.nickname?.takeIf { it.isNotBlank() }?.let { m.uid to it }
                }.toMap()
            }
    }
    val selfUid = session.selfUid.value
    // 保留 uid,以便用会话昵称覆盖显示名(resolved 仅含目录信息)。
    val candidates = memberUids
        .filter { it != selfUid }
        .mapNotNull { uid -> resolved[uid]?.let { uid to it } }
        .filter { (_, info) -> info.id.isNotBlank() }

    // Deselection set — starts empty so the async roster arrives pre-checked.
    var deselected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val picked = candidates.filter { (_, info) -> info.id !in deselected }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = title ?: stringResource(R.string.im_group_voice_call_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(candidates, key = { it.second.id }) { (uid, m) ->
                val checked = m.id !in deselected
                val label = nicknames[uid] ?: m.displayName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deselected =
                                if (checked) deselected + m.id else deselected - m.id
                        }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            deselected =
                                if (checked) deselected + m.id else deselected - m.id
                        },
                    )
                    GroupAvatar(
                        tiles = listOf(GroupTile(m.id, label, m.avatarUrl)),
                        size = 36.dp,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.im_group_voice_call_count, picked.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(4.dp))
            Button(
                enabled = picked.isNotEmpty(),
                onClick = {
                    val toTarget = { uid: String, info: ImUserInfo ->
                        MeetInviteTracker.Target(
                            userId = info.id,
                            label = nicknames[uid] ?: info.displayName,
                            avatarUrl = info.avatarUrl?.takeIf { u -> u.isNotBlank() },
                        )
                    }
                    onCall(
                        picked.map { (uid, info) -> toTarget(uid, info) },
                        candidates.map { (uid, info) -> toTarget(uid, info) },
                    )
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.im_group_voice_call_confirm))
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {}
    }
}
