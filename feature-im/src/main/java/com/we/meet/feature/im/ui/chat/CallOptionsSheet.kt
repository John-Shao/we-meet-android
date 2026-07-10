package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.we.meet.feature.im.R

/**
 * Feishu-style 1:1 call chooser (P0). Three actions: 语音通话 / 视频通话 /
 * 拨打电话. Voice & video both enter a LiveKit room (audio-only vs video) via
 * the host; there is no ringing yet (P1+). 拨打电话 dials the peer's phone via
 * the system dialer — but the backend does not expose peer phone numbers yet,
 * so [peerPhone] is null and that row renders disabled (see P3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallOptionsSheet(
    peerPhone: String?,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onDialPhone: (phone: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CallRow(Icons.Filled.Call, stringResource(R.string.im_plus_call)) {
            onVoiceCall(); onDismiss()
        }
        CallRow(Icons.Filled.Videocam, stringResource(R.string.im_call_video)) {
            onVideoCall(); onDismiss()
        }
        CallRow(
            icon = Icons.Filled.PhoneAndroid,
            label = stringResource(R.string.im_call_phone),
            enabled = peerPhone != null,
        ) {
            peerPhone?.let { onDialPhone(it); onDismiss() }
        }
        Column(Modifier.padding(bottom = 16.dp)) {}
    }
}

@Composable
private fun CallRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Disabled rows dim to Material's standard 38% so 拨打电话 reads as
    // "not available yet" rather than broken.
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
