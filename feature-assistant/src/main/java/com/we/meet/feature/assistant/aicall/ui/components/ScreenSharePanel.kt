package com.we.meet.feature.assistant.aicall.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.screenread.ScreenReadAccessibility
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * In-call screen-share entry and status.
 *
 * The active state also appears in the session notification because the user
 * usually leaves this screen to show another app; the panel is what they see
 * when they come back to stop the share.
 */
@Composable
fun ScreenSharePanel(
    active: Boolean,
    pending: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenOtherApp: () -> Unit,
    onEnableScreenReadAccessibility: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    enabled: Boolean = true,
    available: Boolean = true,
    visible: Boolean = true,
) {
    val palette = WeMeetTheme.extras.aiCall
    val content = if (onDark) palette.onVideo else MaterialTheme.colorScheme.onSurfaceVariant

    if (!active && !pending && !available) {
        Text(
            text = stringResource(R.string.assistant_screen_share_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = modifier,
        )
        return
    }

    if (!active && !pending) {
        FilledTonalButton(
            onClick = onStart,
            enabled = enabled,
            modifier = modifier.testTag("call-screen-share-start"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Spacer(modifier = Modifier.size(Dimens.SpaceS))
            Text(stringResource(R.string.assistant_screen_share_start))
        }
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (onDark) palette.videoScrim else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                        color = content,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
                Spacer(modifier = Modifier.size(Dimens.SpaceS))
                Text(
                    text = stringResource(
                        if (pending) R.string.assistant_screen_share_starting
                        else R.string.assistant_screen_share_active,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.testTag("call-screen-share-stop"),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_screen_share_stop),
                            color = if (onDark) palette.onVideo else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (active) {
                Text(
                    text = stringResource(
                        if (visible) R.string.assistant_screen_share_active_hint
                        else R.string.assistant_screen_share_hidden_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f),
                )
                Text(
                    text = stringResource(R.string.assistant_screen_share_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
                if (onEnableScreenReadAccessibility != null &&
                    !ScreenReadAccessibility.isEnabled(LocalContext.current)) {
                    TextButton(
                        onClick = onEnableScreenReadAccessibility,
                        modifier = Modifier.testTag("call-screen-read-accessibility"),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_screen_read_accessibility),
                            color = if (onDark) palette.onVideo else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Button(
                    onClick = onOpenOtherApp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.assistant_screen_share_go_home))
                }
            }
        }
    }
}
