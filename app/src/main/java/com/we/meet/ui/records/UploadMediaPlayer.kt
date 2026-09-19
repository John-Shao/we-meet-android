package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import com.we.meet.R
import com.we.meet.data.api.dto.RecordMediaDto
import com.we.meet.data.capture.UploadMediaEngine
import com.we.meet.data.capture.WholeFilePlayback
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens

import kotlinx.coroutines.delay

/** How often a playing file reports its clock. Matches the capture engine's cadence. */
private const val POSITION_POLL_MS = 250L

/**
 * Whole-file playback for an imported recording.
 *
 * Reports the source clock through [onPosition] so the transcript can follow, and
 * accepts a seek request so a citation can address an exact millisecond. Both
 * are the same contract the capture player offers, so the reader's sync logic is
 * shared rather than reimplemented per source.
 */
@Composable
internal fun UploadMediaPlayer(
    media: RecordMediaDto,
    positionMs: Long?,
    seek: CaptureAudioSeek? = null,
    onSeekConsumed: () -> Unit = {},
    onPosition: (Long) -> Unit = {},
    /**
     * Fixtures inject a fake so the controls, the position contract and the seek
     * handling can be exercised without opening a real stream. Null means the
     * real engine, built against this composable's own application context.
     */
    createEngine: ((url: String, onInterrupted: () -> Unit) -> WholeFilePlayback)? = null,
) {
    val context = LocalContext.current.applicationContext
    val latestPosition by rememberUpdatedState(onPosition)
    val latestConsume by rememberUpdatedState(onSeekConsumed)
    var engine by remember(media.url) { mutableStateOf<WholeFilePlayback?>(null) }
    var state by remember(media.url) { mutableStateOf("ready") }
    var position by remember(media.url) { mutableLongStateOf(0L) }
    var duration by remember(media.url) { mutableLongStateOf(0L) }
    var rate by remember(media.url) { mutableFloatStateOf(1f) }
    var ratesVisible by remember(media.url) { mutableStateOf(false) }
    var tick by remember(media.url) { mutableIntStateOf(0) }

    fun stop() {
        engine?.close()
        engine = null
        state = "ready"
    }

    DisposableEffect(media.url) {
        onDispose { engine?.close() }
    }

    // Prepared lazily: opening the screen must not fetch a GB-scale file.
    fun start(from: Long) {
        if (state == "error") return
        val current = engine ?: runCatching {
            createEngine?.invoke(media.url) { stop() }
                ?: UploadMediaEngine(context, media.url) { stop() }
        }
            .onFailure { state = "error" }
            .getOrNull() ?: return
        engine = current
        runCatching { current.play(from, rate) }
            .onSuccess { state = "playing"; duration = current.durationMs() }
            .onFailure { stop(); state = "error" }
        tick++
    }

    fun report(milliseconds: Long) {
        position = milliseconds
        latestPosition(milliseconds)
    }

    // One poller for the whole playing lifetime rather than a loop per tick.
    LaunchedEffect(tick, state) {
        while (state == "playing") {
            val current = engine ?: break
            report(current.positionMs())
            if (duration == 0L) duration = current.durationMs()
            if (!current.isPlaying()) {
                state = "ready"
                break
            }
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(seek?.token, engine, state == "error") {
        val request = seek ?: return@LaunchedEffect
        if (state == "error") {
            latestConsume()
            return@LaunchedEffect
        }
        // A citation seek also starts playback, matching the capture player.
        start(request.milliseconds)
        report(request.milliseconds)
        latestConsume()
    }

    val positionLabel = stringResource(R.string.capture_playback_position)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (state == "error") {
                WeMeetInlineErrorState(
                    onRetry = { state = "ready" },
                    message = stringResource(R.string.capture_playback_error),
                )
            } else {
                if (duration == 0L && state != "playing") WeMeetInlineLoading()
                Text("${sourceTime(position)} / ${sourceTime(duration)}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    position.coerceAtMost(maxOf(1L, duration - 1)).toFloat(),
                    onValueChange = { value ->
                        stop()
                        report(value.toLong())
                    },
                    valueRange = 0f..maxOf(1f, (duration - 1).toFloat()),
                    modifier = Modifier.semantics { contentDescription = positionLabel },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ratesVisible = true }) { Text(stringResource(R.string.capture_playback_rate, rate)) }
                    IconButton(onClick = { start(maxOf(0L, position - 15_000)) }) {
                        Icon(Icons.Outlined.Replay, stringResource(R.string.records_skip_back))
                    }
                    FilledTonalIconButton(
                        modifier = Modifier.size(Dimens.ButtonHeight),
                        onClick = { if (state == "playing") { engine?.pause(); state = "ready" } else start(position) },
                    ) {
                        Icon(
                            if (state == "playing") Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (state == "playing") R.string.capture_playback_pause else R.string.capture_playback_play),
                            Modifier.size(Dimens.IconXl),
                        )
                    }
                    IconButton(onClick = { start(minOf(maxOf(0L, duration - 1), position + 15_000)) }) {
                        Icon(Icons.Outlined.FastForward, stringResource(R.string.records_skip_forward))
                    }
                }
            }
        }
    }
    if (ratesVisible) {
        AlertDialog(
            onDismissRequest = { ratesVisible = false },
            title = { Text(stringResource(R.string.capture_playback_speed)) },
            text = {
                Column {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        TextButton(onClick = { rate = speed; ratesVisible = false }) {
                            Text(stringResource(R.string.capture_playback_rate, speed))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ratesVisible = false }) { Text(stringResource(R.string.records_close)) } },
        )
    }
}
