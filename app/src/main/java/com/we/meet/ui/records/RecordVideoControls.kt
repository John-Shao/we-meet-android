@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.OnMediaOverlay
import kotlinx.coroutines.delay

/** The native picture stays mounted while the controls hide or playback pauses. */
@Composable
internal fun RecordVideoControls(
    modifier: Modifier,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    rate: Float,
    fullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onRate: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onCollapse: () -> Unit,
    onFullscreen: () -> Unit,
    picture: @Composable BoxScope.() -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var ratesVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val scrim = MaterialTheme.colorScheme.scrim
    val primary = MaterialTheme.colorScheme.primary
    val end = durationMs.coerceAtLeast(1L)
    LaunchedEffect(playing, interaction, ratesVisible) {
        visible = true
        if (playing && !ratesVisible && !accessibility.isTouchExplorationEnabled) {
            delay(3000)
            visible = false
        }
    }
    Box(modifier.background(scrim)) {
        picture()
        // A separate hit surface leaves native video attachment and its paused frame untouched.
        Box(Modifier.matchParentSize().clickable(onClickLabel = stringResource(R.string.capture_playback_video_controls)) {
            visible = true; interaction++
        })
        if (visible || !playing) {
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.5f), Color.Transparent, scrim.copy(alpha = 0.7f)))))
            CompositionLocalProvider(LocalContentColor provides OnMediaOverlay) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = Dimens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!fullscreen) TextButton(onClick = onCollapse,
                        colors = ButtonDefaults.textButtonColors(contentColor = OnMediaOverlay)) {
                        Icon(Icons.Outlined.ExpandMore, null)
                        Text(stringResource(R.string.capture_playback_hide_video), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { interaction++; onFullscreen() }, modifier = Modifier.size(Dimens.MinTouchTarget)) {
                        Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            stringResource(if (fullscreen) R.string.capture_playback_exit_fullscreen else R.string.capture_playback_fullscreen))
                    }
                }
                RecordPlaybackControls(
                    positionMs = positionMs, durationMs = durationMs, playing = playing, rate = rate,
                    onSeek = onSeek, onSeekFinished = onSeekFinished, onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack, onSkipForward = onSkipForward, onRate = onRate,
                    muted = muted, onToggleMute = onToggleMute,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                    contentColor = OnMediaOverlay, trackColor = OnMediaOverlay.copy(alpha = 0.4f),
                    onInteraction = { interaction++ }, onRateMenuVisibilityChange = { ratesVisible = it },
                )
            }
        } else {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Dimens.RecordPlayback.TrackHeight)) {
                drawLine(OnMediaOverlay.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), size.height)
                drawLine(primary, Offset(0f, center.y), Offset(size.width * positionMs.coerceIn(0L, end) / end, center.y), size.height)
            }
        }
    }
}
