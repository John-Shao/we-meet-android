@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onCollapse: () -> Unit,
    onFullscreen: () -> Unit,
    onMore: (() -> Unit)?,
    picture: @Composable BoxScope.() -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var ratesVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val scrim = MaterialTheme.colorScheme.scrim
    val primary = MaterialTheme.colorScheme.primary
    val positionLabel = stringResource(R.string.capture_playback_position)
    val speedLabel = stringResource(R.string.capture_playback_speed)
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
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!fullscreen) TextButton(onClick = onCollapse, colors = ButtonDefaults.textButtonColors(contentColor = OnMediaOverlay)) {
                        Icon(Icons.Outlined.ExpandMore, null)
                        Text(stringResource(R.string.capture_playback_hide_video), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { ratesVisible = true },
                            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget).semantics { contentDescription = speedLabel },
                            colors = ButtonDefaults.textButtonColors(contentColor = OnMediaOverlay)) {
                            Text(stringResource(R.string.capture_playback_rate, playbackRateValue(rate)))
                        }
                        DropdownMenu(expanded = ratesVisible, onDismissRequest = { ratesVisible = false }) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.capture_playback_rate, playbackRateValue(speed))) },
                                    trailingIcon = { if (speed == rate) Icon(Icons.Outlined.Check, null) },
                                    onClick = { ratesVisible = false; onRate(speed); interaction++ })
                            }
                        }
                    }
                    if (onMore != null) IconButton(onClick = { interaction++; onMore() }) {
                        Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.cd_records_more))
                    }
                }
                IconButton(onClick = { interaction++; onPlayPause() },
                    modifier = Modifier.align(Alignment.Center).size(Dimens.RecordPlayback.PlayButtonSize)
                        .background(scrim.copy(alpha = 0.35f), CircleShape)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (playing) R.string.cd_records_pause else R.string.cd_records_play),
                        Modifier.size(Dimens.IconXl))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceL), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.capture_playback_clock, playbackTime(positionMs), playbackTime(durationMs)),
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { interaction++; onFullscreen() }) {
                            Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                stringResource(if (fullscreen) R.string.capture_playback_exit_fullscreen else R.string.capture_playback_fullscreen))
                        }
                    }
                    Slider(value = positionMs.coerceIn(0L, end).toFloat(), valueRange = 0f..end.toFloat(), enabled = durationMs > 0,
                        onValueChange = { interaction++; onSeek(it.toLong()) }, onValueChangeFinished = onSeekFinished,
                        modifier = Modifier.fillMaxWidth().height(Dimens.MinTouchTarget).semantics { contentDescription = positionLabel },
                        thumb = { Box(Modifier.offset(y = Dimens.SpaceL).size(Dimens.RecordPlayback.ThumbSize).background(primary, CircleShape)) },
                        track = { slider -> Canvas(Modifier.offset(y = Dimens.SpaceL).fillMaxWidth().height(Dimens.RecordPlayback.TrackHeight)) {
                            drawLine(OnMediaOverlay.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), size.height, StrokeCap.Round)
                            drawLine(primary, Offset(0f, center.y), Offset(size.width * slider.value / end, center.y), size.height, StrokeCap.Round)
                        } })
                }
            }
        } else {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Dimens.RecordPlayback.TrackHeight)) {
                drawLine(OnMediaOverlay.copy(alpha = 0.4f), Offset(0f, center.y), Offset(size.width, center.y), size.height)
                drawLine(primary, Offset(0f, center.y), Offset(size.width * positionMs.coerceIn(0L, end) / end, center.y), size.height)
            }
        }
    }
}
