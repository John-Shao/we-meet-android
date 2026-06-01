package com.we.meet.feature.assistant.aicall.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus

@Composable
fun BottomControls(
    status: AiCallStatus,
    mode: AiCallMode,
    isMicMuted: Boolean,
    onToggleMic: () -> Unit,
    onPrimaryAction: () -> Unit,
    onToggleVideoMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = status is AiCallStatus.Active
    val isConnecting = status is AiCallStatus.Connecting
    val videoSelected = mode == AiCallMode.Video

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left — mic mute: always visible, enabled only during Active call.
        MicButton(
            enabled = isActive,
            muted = isMicMuted,
            onClick = onToggleMic,
        )

        // Centre — primary call / hangup.
        CallButton(
            isActive = isActive || isConnecting,
            onClick = onPrimaryAction,
        )

        // Right — video toggle: always visible, disabled while connecting.
        VideoToggleButton(
            selected = videoSelected,
            enabled = !isConnecting,
            onClick = onToggleVideoMode,
        )
    }
}

@Composable
private fun MicButton(enabled: Boolean, muted: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0xFFEDEDED) else Color(0xFFEDEDED).copy(alpha = 0.5f)
    val tint = when {
        !enabled -> Color.Black.copy(alpha = 0.35f)
        muted -> Color(0xFFE0524C)
        else -> Color.Black
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = if (muted) "取消静音" else "静音",
            tint = tint,
        )
    }
}

@Composable
private fun CallButton(isActive: Boolean, onClick: () -> Unit) {
    val bg = if (isActive) Color(0xFFE0524C) else Color(0xFF1FB85F)
    val icon = if (isActive) Icons.Filled.CallEnd else Icons.Filled.Call
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (isActive) "挂断" else "开始通话",
            tint = Color.White,
        )
    }
}

@Composable
private fun VideoToggleButton(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFFEDEDED).copy(alpha = 0.5f)
        selected -> Color.White
        else -> Color(0xFFEDEDED)
    }
    val tint = when {
        !enabled -> Color.Black.copy(alpha = 0.35f)
        else -> Color.Black
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = "切换视频模式",
            tint = tint,
        )
    }
}
