package com.we.meet.feature.im.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.ui.common.GroupAvatar
import kotlinx.coroutines.delay

/**
 * Feishu/WeCom-style minimal in-call screen for a 1:1 **voice** call — avatar +
 * name + mm:ss duration, and a mic / hangup / speaker⇌earpiece control row.
 * Replaces the full meeting grid once a voice call connects.
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
) {
    val session = remember(deps) { ImSession.get(deps) }
    LaunchedEffect(peerUid) { session.userDirectory.requestResolve(listOf(peerUid)) }
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val peer = remember(peerUid, directoryVersion) { session.userDirectory.get(peerUid) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() } ?: fallbackName

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GroupAvatar(
                tiles = listOf(GroupTile(peerUid, displayName, peer?.avatarUrl)),
                size = 112.dp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp, start = 32.dp, end = 32.dp),
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
                label = stringResource(R.string.im_action_cancel),
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
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = background,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.size(64.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatElapsed(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

private val HangupRed = Color(0xFFE5484D)
private val AcceptGreen = Color(0xFF30A46C)
private val NeutralControl = Color(0xFF6B7280)
private val MutedControl = Color(0xFF9CA3AF)
