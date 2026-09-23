@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import com.we.meet.ui.theme.Dimens
import com.we.meet.R

/** Shared by the transcript and both players; explicit resume also clears filters. */
@Stable
internal class TranscriptFollowState {
    var following by mutableStateOf(true)
    var resumeToken by mutableIntStateOf(0)
        private set

    fun resume() { following = true; resumeToken++ }
    fun toggle() { if (following) following = false else resume() }
}

internal fun playbackRateValue(rate: Float): String =
    rate.toBigDecimal().stripTrailingZeros().toPlainString()

internal fun playbackTime(milliseconds: Long): String = sourceTime(milliseconds).padStart(5, '0')

@Composable
internal fun RecordPlayerSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Dimens.RecordPlayback.CornerRadius, topEnd = Dimens.RecordPlayback.CornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Dimens.ElevationFlat,
    ) {
        Column(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS), content = content)
    }
}

/** The visual track is thin, while the slider and secondary controls retain 48dp targets. */
@Composable
internal fun RecordPlaybackControls(
    positionMs: Long,
    durationMs: Long?,
    playing: Boolean,
    rate: Float,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onRate: (Float) -> Unit,
    followState: TranscriptFollowState? = null,
    onSeekFinished: () -> Unit = {},
    onMore: (() -> Unit)? = null,
) {
    var ratesVisible by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val positionLabel = stringResource(R.string.capture_playback_position)
    val speedLabel = stringResource(R.string.capture_playback_speed)
    val followLabel = stringResource(R.string.capture_playback_follow)
    val enabled = durationMs != null && durationMs > 0
    val end = (durationMs ?: 0L).coerceAtLeast(1L)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = positionMs.coerceIn(0L, end).toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            onValueChangeFinished = onSeekFinished,
            enabled = enabled,
            valueRange = 0f..end.toFloat(),
            modifier = Modifier.fillMaxWidth().height(Dimens.MinTouchTarget).semantics { contentDescription = positionLabel },
            thumb = {
                Box(Modifier.size(Dimens.RecordPlayback.ThumbSize).background(if (enabled) colors.primary else colors.outline, CircleShape))
            },
            track = { slider ->
                Canvas(Modifier.fillMaxWidth().height(Dimens.RecordPlayback.TrackHeight)) {
                    val fraction = (slider.value / end.toFloat()).coerceIn(0f, 1f)
                    val start = if (rtl) size.width else 0f
                    val finish = if (rtl) 0f else size.width
                    drawLine(colors.surfaceContainerHighest, Offset(start, center.y), Offset(finish, center.y),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                    if (fraction > 0f) drawLine(if (enabled) colors.primary else colors.outline,
                        Offset(start, center.y), Offset(start + (finish - start) * fraction, center.y),
                        strokeWidth = size.height, cap = StrokeCap.Round)
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(positionMs), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant)
            Text(durationMs?.let(::playbackTime) ?: "—", style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(Dimens.SpaceS))
        // Equal outer slots keep the primary action centered, including when follow is hidden.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { ratesVisible = true }, contentPadding = PaddingValues(Dimens.SpaceXs),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                    modifier = Modifier.heightIn(min = Dimens.MinTouchTarget).semantics { contentDescription = speedLabel }) {
                    Text(stringResource(R.string.capture_playback_rate, playbackRateValue(rate)), maxLines = 1)
                }
                DropdownMenu(expanded = ratesVisible, onDismissRequest = { ratesVisible = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.capture_playback_rate, playbackRateValue(speed))) },
                            trailingIcon = { if (speed == rate) Icon(Icons.Outlined.Check, null) },
                            onClick = { ratesVisible = false; onRate(speed) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SkipFifteenButton(false, onSkipBack)
            }
            FilledIconButton(onClick = onPlayPause,
                modifier = Modifier.width(Dimens.RecordPlayback.PlayButtonWidth).height(Dimens.RecordPlayback.PlayButtonSize),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary.copy(alpha = 0.08f), contentColor = colors.primary)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.cd_records_pause else R.string.cd_records_play), Modifier.size(Dimens.RecordPlayback.PlayIconSize))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SkipFifteenButton(true, onSkipForward, enabled)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.size(Dimens.MinTouchTarget)) {
                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.cd_records_more))
                } else if (followState != null) IconToggleButton(
                    checked = followState.following, onCheckedChange = { followState.toggle() },
                    modifier = Modifier.size(Dimens.MinTouchTarget).semantics { contentDescription = followLabel },
                    colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = colors.primary),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.MyLocation, null, Modifier.size(Dimens.ComponentIconMedium))
                        Text(followLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkipFifteenButton(forward: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    // A circular arrow with an explicit interval avoids confusing skip with replay/fast-forward.
    val label = stringResource(if (forward) R.string.cd_records_skip_forward else R.string.cd_records_skip_back)
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(Dimens.MinTouchTarget).semantics { contentDescription = label }) {
        Box(Modifier.size(Dimens.IconXl).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Replay, null, Modifier.size(Dimens.IconXl).graphicsLayer { scaleX = if (forward) -1f else 1f })
            Text("15", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = Dimens.SpaceXs))
        }
    }
}
