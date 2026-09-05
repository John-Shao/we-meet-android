package com.we.meet.feature.assistant.aicall.ui.components

import com.we.meet.ui.theme.AiCallControlColors
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
import com.we.meet.feature.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus

@Composable
fun BottomControls(
    status: AiCallStatus,
    mode: AiCallMode,
    isMicMuted: Boolean,
    micPending: Boolean,
    onToggleMic: () -> Unit,
    onPrimaryAction: () -> Unit,
    onToggleVideoMode: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 控件压在摄像头画面上(视频已开)。此时恒用深色那套配色,不看主题 ——
     * 与顶栏 / 状态提示条的 `onDark` 同一套判断。
     */
    onDark: Boolean = false,
) {
    val isActive = status is AiCallStatus.Active
    val isConnecting = status is AiCallStatus.Connecting
    val videoSelected = mode == AiCallMode.Video
    // 中性控件的配色:压在视频上恒深色,否则跟随主题。挂断/发起是实底红绿,
    // 深浅一致,不走这里。
    val palette = WeMeetTheme.extras.aiCall
    val controls = if (onDark) palette.controlOnDark else palette.control

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceXxl, vertical = Dimens.SpaceXl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left — mic mute: always visible, enabled only during Active call.
        MicButton(
            controls = controls,
            enabled = isActive && !micPending,
            loading = micPending,
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
            controls = controls,
            selected = videoSelected,
            enabled = !isConnecting,
            onClick = onToggleVideoMode,
        )
    }
}

@Composable
private fun MicButton(
    controls: AiCallControlColors,
    enabled: Boolean,
    loading: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
) {
    val hangUp = WeMeetTheme.extras.aiCall.hangUp
    val bg = if (enabled) controls.surface else controls.surface.copy(alpha = 0.5f)
    val tint = when {
        !enabled -> controls.onSurface.copy(alpha = 0.35f)
        // 静音是「出事了」的状态,用通话红标出来。压在圆底上 3.34:1(浅)/
        // 3.49:1(深),两边都过 SC 1.4.11 的 3:1。
        muted -> hangUp
        else -> controls.onSurface
    }
    val actionLabel = stringResource(
        if (muted) R.string.assistant_cd_unmute else R.string.assistant_cd_mute,
    )
    val stateLabel = stringResource(
        if (muted) R.string.assistant_state_muted else R.string.assistant_state_unmuted,
    )
    Box(
        modifier = Modifier
            .size(Dimens.AiCall.ControlButton)
            .clip(CircleShape)
            .background(bg)
            .toggleable(
                value = muted,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onClick() },
            )
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconSmall),
                color = controls.onSurface,
                strokeWidth = Dimens.BorderEmphasis,
            )
        } else {
            Icon(
                imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = null,
                tint = tint,
            )
        }
    }
}

@Composable
private fun CallButton(isActive: Boolean, onClick: () -> Unit) {
    val palette = WeMeetTheme.extras.aiCall
    val bg = if (isActive) palette.hangUp else palette.startCall
    val icon = if (isActive) Icons.Filled.CallEnd else Icons.Filled.Call
    val actionLabel = stringResource(
        if (isActive) R.string.assistant_cd_hang_up else R.string.assistant_cd_start_call,
    )
    Box(
        modifier = Modifier
            .size(Dimens.AiCall.CallButton)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick,
            )
            .semantics { contentDescription = actionLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.onCallAction,
        )
    }
}

@Composable
private fun VideoToggleButton(
    controls: AiCallControlColors,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> controls.surface.copy(alpha = 0.5f)
        selected -> controls.selected
        else -> controls.surface
    }
    val tint = when {
        !enabled -> controls.onSurface.copy(alpha = 0.35f)
        selected -> controls.onSelected
        else -> controls.onSurface
    }
    val actionLabel = stringResource(
        if (selected) R.string.assistant_cd_switch_to_voice else R.string.assistant_cd_switch_to_video,
    )
    val stateLabel = stringResource(
        if (selected) R.string.assistant_state_video else R.string.assistant_state_voice,
    )
    Box(
        modifier = Modifier
            .size(Dimens.AiCall.ControlButton)
            .clip(CircleShape)
            .background(bg)
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onClick() },
            )
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = tint,
        )
    }
}
