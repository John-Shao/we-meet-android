package com.we.meet.ui.records

import android.view.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.SystemClock
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
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
    onDuration: (Long?) -> Unit = {},
    /**
     * Fixtures inject a fake so the controls, the position contract and the seek
     * handling can be exercised without opening a real stream. Null means the
     * real engine, built against this composable's own application context.
     */
    createEngine: ((url: String, onInterrupted: () -> Unit) -> WholeFilePlayback)? = null,
    sourceId: String = media.name,
    /** Bounds native HTTP preparation retries; tests use a shorter real-clock deadline. */
    preparationTimeoutMs: Long = PREPARATION_TIMEOUT_MS,
    followState: TranscriptFollowState? = null,
) {
    val context = LocalContext.current.applicationContext
    val latestPosition by rememberUpdatedState(onPosition)
    val latestDuration by rememberUpdatedState(onDuration)
    val latestConsume by rememberUpdatedState(onSeekConsumed)
    var engine by remember(sourceId) { mutableStateOf<WholeFilePlayback?>(null) }
    var state by remember(sourceId) { mutableStateOf(MediaPlaybackState.Ready) }
    var position by remember(sourceId) { mutableLongStateOf(positionMs ?: 0L) }
    var duration by remember(sourceId) { mutableLongStateOf(0L) }
    var rate by remember(sourceId) { mutableFloatStateOf(1f) }
    var muted by remember(sourceId) { mutableStateOf(false) }
    var videoExpanded by remember(sourceId) { mutableStateOf(true) }
    var fullscreen by remember(sourceId) { mutableStateOf(false) }
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
        state = MediaPlaybackState.Ready
    }

    DisposableEffect(sourceId) {
        latestDuration(null)
        onDispose { engine?.close(); latestDuration(null) }
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
            .onFailure { state = MediaPlaybackState.Error }
            .getOrNull() ?: return
        engine = current
        runCatching { current.setSurface(surface); current.setMuted(muted); current.play(from, rate) }
            .onSuccess {
                state = if (current.isPreparing()) MediaPlaybackState.Preparing else MediaPlaybackState.Playing
                if (state == MediaPlaybackState.Preparing) preparationStartedAt = SystemClock.elapsedRealtime()
                duration = current.durationMs()
                latestDuration(duration.takeIf(::validMediaDuration))
            }
            .onFailure { stop(); state = MediaPlaybackState.Error }
        tick++
    }

    fun report(milliseconds: Long) {
        position = milliseconds
        latestPosition(milliseconds)
    }

    fun jump(milliseconds: Long) {
        start(milliseconds)
        report(milliseconds)
        followState?.resume()
    }

    // One poller for the whole playing lifetime rather than a loop per tick.
    LaunchedEffect(tick, state) {
        while (state.showsPause) {
            val current = engine ?: break
            if (current.failure() != null) {
                if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                else { stop(); state = MediaPlaybackState.Error }
                break
            }
            if (current.isPreparing()) {
                // Native HTTP may repeatedly retry 403 without an error callback.
                // A healthy prepared stream remains untouched by lease refreshes.
                if (SystemClock.elapsedRealtime() - preparationStartedAt >= preparationTimeoutMs) {
                    if (openedUrl != latestMedia.url && automaticRecoveries < 1) start(position, automatic = true)
                    else { stop(); state = MediaPlaybackState.Error }
                    break
                }
                delay(POSITION_POLL_MS)
                continue
            }
            report(current.positionMs())
            duration = current.durationMs()
            latestDuration(duration.takeIf(::validMediaDuration))
            aspect = current.videoAspectRatio()
            if (state == MediaPlaybackState.Preparing) {
                state = MediaPlaybackState.Playing
                automaticRecoveries = 0
            }
            if (!current.isPlaying()) {
                state = MediaPlaybackState.Ready
                break
            }
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(seek?.token) {
        val request = seek ?: return@LaunchedEffect
        if (state == MediaPlaybackState.Error) {
            latestConsume()
            return@LaunchedEffect
        }
        // A citation seek also starts playback, matching the capture player.
        jump(request.milliseconds)
        latestConsume()
    }

    val controls: @Composable () -> Unit = {
        if (state == MediaPlaybackState.Error) {
            WeMeetInlineErrorState(onRetry = { stop(); start(position) },
                message = stringResource(R.string.capture_playback_error))
        } else {
            RecordPlaybackControls(
                positionMs = position, durationMs = duration.takeIf(::validMediaDuration),
                playing = state.showsPause, rate = rate,
                onSeek = { value ->
                    engine?.pause()
                    state = MediaPlaybackState.Ready
                    report(value)
                    latestConsume()
                },
                onSeekFinished = { engine?.seekTo(position); followState?.resume() },
                onPlayPause = {
                    if (state.showsPause) { engine?.pause(); state = MediaPlaybackState.Ready }
                    else start(if (duration > 0 && position >= duration) 0 else position)
                },
                onSkipBack = { jump(maxOf(0L, position - 15_000)) },
                onSkipForward = { jump(minOf(maxOf(0L, duration - 1), position + 15_000)) },
                onRate = { speed -> rate = speed; if (state.showsPause) start(position) },
            )
        }
    }
    val hasVideo = media.mediaType == "video"
    val attachSurface: (Surface) -> Unit = {
        surface = it
        engine?.let { current ->
            current.setSurface(it)
            // Redraw a paused frame after expanding or leaving full screen without autoplay.
            if (!state.showsPause && !current.isPreparing()) current.seekTo(position)
        }
    }
    val detachSurface: (Surface) -> Unit = {
        // A removed preview must not detach a newer full-screen surface.
        if (surface === it) { engine?.setSurface(null); surface = null }
    }
    val videoPanel: @Composable (Modifier) -> Unit = { modifier ->
        RecordVideoControls(
            modifier = modifier, playing = state.showsPause,
            positionMs = position, durationMs = duration, rate = rate, fullscreen = fullscreen,
            onPlayPause = {
                if (state.showsPause) { engine?.pause(); state = MediaPlaybackState.Ready }
                else start(if (duration > 0 && position >= duration) 0 else position)
            },
            onSeek = { value -> engine?.pause(); state = MediaPlaybackState.Ready; report(value); latestConsume() },
            onSeekFinished = { engine?.seekTo(position); followState?.resume() },
            onRate = { speed -> rate = speed; if (state.showsPause) start(position) },
            onSkipBack = { jump(maxOf(0L, position - 15_000)) },
            onSkipForward = { jump(minOf(maxOf(0L, duration - 1), position + 15_000)) },
            muted = muted, onToggleMute = { muted = !muted; engine?.setMuted(muted) },
            onCollapse = { videoExpanded = false }, onFullscreen = { fullscreen = !fullscreen },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val videoHeight = minOf(maxWidth / aspect, maxHeight)
                RecordVideoSurface(Modifier.size(videoHeight * aspect, videoHeight), attachSurface, detachSurface)
            }
        }
    }
    if (hasVideo && videoExpanded && !fullscreen && state != MediaPlaybackState.Error) {
        val heightCap = (LocalConfiguration.current.screenHeightDp * Dimens.MediaPreviewMaxHeightRatio).dp
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Bound the top preview so the transcript retains reading space below it.
            videoPanel(Modifier.fillMaxWidth().height(minOf(maxWidth / (16f / 9f), heightCap).coerceAtLeast(Dimens.RecordPlayback.VideoMinHeight)))
        }
    } else if (!fullscreen) RecordPlayerSurface {
        if (hasVideo) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { fullscreen = true }) {
                Icon(Icons.Outlined.Fullscreen, stringResource(R.string.capture_playback_fullscreen))
            }
            TextButton(onClick = { videoExpanded = true }) {
                Icon(Icons.Outlined.ExpandLess, null)
                Text(stringResource(R.string.capture_playback_show_video))
            }
        }
        controls()
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (state == MediaPlaybackState.Error) Column {
                    IconButton(onClick = { fullscreen = false }) {
                        Icon(Icons.Outlined.FullscreenExit, stringResource(R.string.capture_playback_exit_fullscreen), tint = com.we.meet.ui.theme.OnMediaOverlay)
                    }
                    controls()
                } else videoPanel(Modifier.fillMaxSize())
            }
        }
    }
}

/** The native surface survives pause; only explicit collapse/full-screen moves it. */
@Composable
private fun RecordVideoSurface(
    modifier: Modifier,
    onAttach: (Surface) -> Unit,
    onDetach: (Surface) -> Unit,
) {
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
    val label = stringResource(R.string.capture_playback_video_preview)
    AndroidView(
        modifier = modifier.clipToBounds().background(MaterialTheme.colorScheme.scrim)
            .semantics { contentDescription = label },
        factory = { context -> SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { attach(holder.surface) }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { attach(holder.surface) }
                override fun surfaceDestroyed(holder: SurfaceHolder) { detach(holder.surface) }
            })
        } },
    )
}
