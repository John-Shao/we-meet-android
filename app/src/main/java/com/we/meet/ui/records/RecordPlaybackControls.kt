@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import com.we.meet.ui.theme.Dimens
import com.we.meet.R

/** Shared by the transcript and both players; explicit resume also clears filters. */
@Stable
internal class TranscriptFollowState {
    var following by mutableStateOf(true)
    var resumeToken by mutableIntStateOf(0)
        private set

    var canResume: () -> Boolean = { true }

    fun resume() {
        if (!canResume()) return
        following = true
        resumeToken++
    }
}

internal fun playbackRateValue(rate: Float): String =
    rate.toBigDecimal().stripTrailingZeros().toPlainString()

internal fun playbackTime(milliseconds: Long): String = sourceTime(milliseconds).padStart(5, '0')

@Composable
internal fun RecordPlayerSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Dimens.ElevationFlat,
    ) {
        Column(Modifier.padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs), content = content)
    }
}

/** One control strip for audio, collapsed video and video overlays. */
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
    muted: Boolean,
    onToggleMute: () -> Unit,
    onSeekFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    compactTopSpacing: Boolean = false,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    playContentColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    onInteraction: () -> Unit = {},
    onRateMenuVisibilityChange: (Boolean) -> Unit = {},
) {
    var ratesVisible by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val enabled = durationMs != null && durationMs > 0
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val positionLabel = stringResource(R.string.capture_playback_position)
    val speedLabel = stringResource(R.string.capture_playback_speed)
    val clockLabel = stringResource(R.string.capture_playback_clock, playbackTime(positionMs),
        if (durationMs != null && durationMs > 0) playbackTime(durationMs) else "\u2014")
    val rateLabel = stringResource(R.string.capture_playback_rate, playbackRateValue(rate))
    val detailStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val clockWidth = with(density) {
        textMeasurer.measure(clockLabel, detailStyle, softWrap = false, maxLines = 1).size.width.toDp()
    }
    val rateWidth = maxOf(Dimens.MinTouchTarget, with(density) {
        textMeasurer.measure(rateLabel, detailStyle, softWrap = false, maxLines = 1).size.width.toDp()
    } + Dimens.SpaceXxs * 2)
    val end = (durationMs ?: 0L).coerceAtLeast(1L)
    val timelineHeight = if (compactTopSpacing) Dimens.ControlCompact else Dimens.MinTouchTarget
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(modifier.fillMaxWidth()) {
            Slider(value = positionMs.coerceIn(0L, end).toFloat(), valueRange = 0f..end.toFloat(), enabled = enabled,
                onValueChange = { onInteraction(); onSeek(it.toLong()) }, onValueChangeFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth().height(timelineHeight).semantics { contentDescription = positionLabel },
                // Keep the painted rail inside Slider's measured hit region, including over video.
                thumb = {
                    Box(Modifier.width(Dimens.RecordPlayback.ThumbSize).height(timelineHeight)) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.SpaceXxs)
                            .size(Dimens.RecordPlayback.ThumbSize).background(primary, CircleShape))
                    }
                },
                track = { slider -> Box(Modifier.fillMaxWidth().height(timelineHeight)) {
                    Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(bottom = Dimens.SpaceXs + Dimens.SpaceXxs)
                        .height(Dimens.RecordPlayback.TrackHeight).testTag("record-playback-track")) {
                        val start = if (rtl) size.width else 0f
                        val finish = if (rtl) 0f else size.width
                        drawLine(trackColor, Offset(start, center.y), Offset(finish, center.y), size.height, StrokeCap.Round)
                        drawLine(primary, Offset(start, center.y), Offset(start + (finish - start) * slider.value / end, center.y), size.height, StrokeCap.Round)
                    }
                } })
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Measure with the current font scale instead of forcing every phone into two rows.
                val requiredWidth = Dimens.MinTouchTarget * 4 + rateWidth + clockWidth + Dimens.SpaceXs * 2
                val compact = maxWidth < requiredWidth
                val transport: @Composable RowScope.() -> Unit = {
                    IconButton(onClick = { onInteraction(); onPlayPause() }, modifier = Modifier.size(Dimens.MinTouchTarget),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = playContentColor)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (playing) R.string.cd_records_pause else R.string.cd_records_play), Modifier.size(Dimens.IconLarge))
                    }
                    SkipFifteenButton(false, { onInteraction(); onSkipBack() }, enabled = positionMs > 0)
                    SkipFifteenButton(true, { onInteraction(); onSkipForward() }, enabled = !enabled || positionMs < end)
                    IconToggleButton(checked = muted, onCheckedChange = { onInteraction(); onToggleMute() },
                        modifier = Modifier.size(Dimens.MinTouchTarget),
                        colors = IconButtonDefaults.iconToggleButtonColors(contentColor = contentColor, checkedContentColor = contentColor)) {
                        Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            stringResource(if (muted) R.string.capture_playback_unmute else R.string.capture_playback_mute))
                    }
                }
                val details: @Composable RowScope.() -> Unit = {
                    Box {
                        TextButton(onClick = { ratesVisible = true; onRateMenuVisibilityChange(true); onInteraction() },
                            modifier = Modifier.width(rateWidth).height(Dimens.MinTouchTarget).semantics { contentDescription = speedLabel },
                            contentPadding = PaddingValues(Dimens.SpaceXxs),
                            colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
                            Text(rateLabel, style = detailStyle, maxLines = 1, softWrap = false)
                        }
                        DropdownMenu(expanded = ratesVisible, onDismissRequest = { ratesVisible = false; onRateMenuVisibilityChange(false) }) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.capture_playback_rate, playbackRateValue(speed))) },
                                    trailingIcon = { if (speed == rate) Icon(Icons.Outlined.Check, null) },
                                    onClick = { ratesVisible = false; onRateMenuVisibilityChange(false); onRate(speed); onInteraction() })
                            }
                        }
                    }
                    Text(clockLabel, style = detailStyle, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXs))
                }
                if (compact) Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically, content = transport)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = details)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    transport()
                    Spacer(Modifier.weight(1f))
                    details()
                }
            }
        }
    }
}

@Composable
internal fun SkipFifteenButton(forward: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
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
