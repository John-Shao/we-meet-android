package com.we.meet.ui.records

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.SystemClock
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.key
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
private const val PREPARATION_TIMEOUT_MS = 30_000L

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
    sourceId: String = media.name,
    /** Bounds native HTTP preparation retries; tests use a shorter real-clock deadline. */
    preparationTimeoutMs: Long = PREPARATION_TIMEOUT_MS,
) {
    val context = LocalContext.current.applicationContext
    val latestPosition by rememberUpdatedState(onPosition)
    val latestConsume by rememberUpdatedState(onSeekConsumed)
    var engine by remember(sourceId) { mutableStateOf<WholeFilePlayback?>(null) }
    var state by remember(sourceId) { mutableStateOf("ready") }
    var position by remember(sourceId) { mutableLongStateOf(positionMs ?: 0L) }
    var duration by remember(sourceId) { mutableLongStateOf(0L) }
    var rate by remember(sourceId) { mutableFloatStateOf(1f) }
    var ratesVisible by remember(sourceId) { mutableStateOf(false) }
    var tick by remember(sourceId) { mutableIntStateOf(0) }
    var preparationStartedAt by remember(sourceId) { mutableLongStateOf(0) }
    var automaticRecoveries by remember(sourceId) { mutableIntStateOf(0) }

    var openedUrl by remember(sourceId) { mutableStateOf<String?>(null) }
    var surface by remember(sourceId) { mutableStateOf<Surface?>(null) }
    var aspect by remember(sourceId) { mutableFloatStateOf(16f / 9f) }
    val latestMedia by rememberUpdatedState(media)

    fun stop() {
        engine?.close()
        engine = null
        state = "ready"
    }

    DisposableEffect(sourceId) {
        onDispose { engine?.close() }
    }

    // Prepared lazily: opening the screen must not fetch a GB-scale file.
    fun start(from: Long, automatic: Boolean = false) {
        automaticRecoveries = if (automatic) automaticRecoveries + 1 else 0
        // A refreshed lease does not interrupt an existing stream. The next
        // explicit play/seek uses the latest URL while preserving source time.
        if (engine != null && openedUrl != latestMedia.url) stop()
        val current = engine ?: runCatching {
            openedUrl = latestMedia.url
            createEngine?.invoke(latestMedia.url) { stop() }
                ?: UploadMediaEngine(context, latestMedia.url) { stop() }
        }
            .onFailure { state = "error" }
            .getOrNull() ?: return
        engine = current
        runCatching { current.setSurface(surface); current.play(from, rate) }
            .onSuccess {
                state = if (current.isPreparing()) "loading" else "playing"
                if (state == "loading") preparationStartedAt = SystemClock.elapsedRealtime()
                duration = current.durationMs()
            }
            .onFailure { stop(); state = "error" }
        tick++
    }

    fun report(milliseconds: Long) {
        position = milliseconds
        latestPosition(milliseconds)
    }

    // One poller for the whole playing lifetime rather than a loop per tick.
    LaunchedEffect(tick, state) {
        while (state == "playing" || state == "loading") {
            val current = engine ?: break
            if (current.failure() != null) {
                if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                else { stop(); state = "error" }
                break
            }
            if (current.isPreparing()) {
                // Native HTTP may repeatedly retry 403 without an error callback.
                // A healthy prepared stream remains untouched by lease refreshes.
                if (SystemClock.elapsedRealtime() - preparationStartedAt >= preparationTimeoutMs) {
                    if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                    else { stop(); state = "error" }
                    break
                }
                delay(POSITION_POLL_MS)
                continue
            }
            report(current.positionMs())
            duration = current.durationMs()
            aspect = current.videoAspectRatio()
            if (state == "loading") {
                state = "playing"
                automaticRecoveries = 0
            }
            if (!current.isPlaying()) {
                state = "ready"
                break
            }
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(seek?.token) {
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

    val videoMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.3f).dp
    val positionLabel = stringResource(R.string.capture_playback_position)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            if (media.mediaType == "video") key(sourceId) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Size both dimensions before measuring the native SurfaceView.
                    // A forced full width plus a height cap can make aspectRatio
                    // overflow its measured bounds for portrait videos.
                    val videoHeight = minOf(maxWidth / aspect, videoMaxHeight)
                    Box(
                        Modifier.fillMaxWidth().height(videoHeight).clipToBounds().background(MaterialTheme.colorScheme.scrim),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            modifier = Modifier.size(width = videoHeight * aspect, height = videoHeight),
                            factory = { viewContext -> SurfaceView(viewContext).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface; engine?.setSurface(surface) }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { engine?.setSurface(holder.surface) }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) { engine?.setSurface(null); surface = null }
                                })
                            } },
                        )
                    }
                }
            }
            if (state == "error") {
                WeMeetInlineErrorState(
                    onRetry = { stop(); start(position) },
                    message = stringResource(R.string.capture_playback_error),
                )
            } else {
                if (state == "loading") WeMeetInlineLoading()
                Text("${sourceTime(position)} / ${sourceTime(duration)}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    position.coerceAtMost(maxOf(1L, duration - 1)).toFloat(),
                    onValueChange = { value ->
                        stop()
                        report(value.toLong())
                    },
                    enabled = duration > 0,
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
                        onClick = { if (state == "playing" || state == "loading") { engine?.pause(); state = "ready" } else start(position) },
                    ) {
                        Icon(
                            if (state == "playing" || state == "loading") Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (state == "playing" || state == "loading") R.string.capture_playback_pause else R.string.capture_playback_play),
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
                        TextButton(onClick = { rate = speed; ratesVisible = false; if (state == "playing") start(position) }) {
                            Text(stringResource(R.string.capture_playback_rate, speed))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ratesVisible = false }) { Text(stringResource(R.string.records_close)) } },
        )
    }
}
