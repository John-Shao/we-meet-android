package com.we.meet.ui.records

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.capture.AndroidCapturePlaybackOutput
import com.we.meet.data.capture.CapturePlaybackEngine
import com.we.meet.data.capture.CapturePlaybackRegistry
import com.we.meet.data.capture.playbackFailureReason
import android.util.Log
import com.we.meet.data.repository.CapturePlaybackRepository
import com.we.meet.data.repository.CapturePlaylist
import com.we.meet.service.CaptureForegroundService
import com.we.meet.service.ConferenceForegroundService
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import java.util.UUID
import kotlinx.coroutines.*

internal data class CaptureAudioSeek(val milliseconds: Long, val token: String = UUID.randomUUID().toString())

@Composable
internal fun NativeCaptureAudioPlayer(viewer: String, recordId: String, repository: CapturePlaybackRepository, currentViewer: () -> String?, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}, onPosition: (Long) -> Unit = {}, followState: TranscriptFollowState? = null) {
    val context = LocalContext.current.applicationContext
    CaptureAudioPlayer(viewer, recordId, { repository.playlist(viewer, recordId).getOrThrow() }, { allowed ->
        CapturePlaybackEngine({ playlist, index -> repository.audio(viewer, playlist, index).getOrThrow() },
            { repository.checkAccess(viewer, it).getOrThrow() }, { AndroidCapturePlaybackOutput(context, it) }, allowed)
    }, { currentViewer() == viewer && !CaptureForegroundService.microphoneActive && !ConferenceForegroundService.isRunning }, seek, onSeekConsumed, onPosition, followState)
}

/**
 * Foreground-only player; fixtures inject verified audio and a silent output without real APIs.
 *
 * [onPosition] reports the source-clock position so a transcript can follow playback;
 * it fires on seeks and on every progress tick while playing.
 */
@Composable
internal fun CaptureAudioPlayer(viewer: String, recordId: String, load: suspend () -> CapturePlaylist, createEngine: (allowed: () -> Boolean) -> CapturePlaybackEngine,
    authorized: () -> Boolean, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}, onPosition: (Long) -> Unit = {}, followState: TranscriptFollowState? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentAllowed by rememberUpdatedState(authorized)
    val consumeSeek by rememberUpdatedState(onSeekConsumed)
    val reportPosition by rememberUpdatedState(onPosition)
    var playlist by remember(viewer, recordId) { mutableStateOf<CapturePlaylist?>(null) }
    var state by remember(viewer, recordId) { mutableStateOf(MediaPlaybackState.Loading) }
    var position by remember(viewer, recordId) { mutableLongStateOf(0) }
    var rate by remember(viewer, recordId) { mutableFloatStateOf(1f) }
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    var engine by remember(viewer, recordId) { mutableStateOf<CapturePlaybackEngine?>(null) }
    var work by remember(viewer, recordId) { mutableStateOf<Job?>(null) }
    val allowed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentAllowed() }

    /**
     * Single writer for the source clock, so a follower cannot miss a change.
     * Every position update goes through here — including the slider drag and the
     * engine's progress callback — instead of assigning the state directly.
     */
    fun setPosition(milliseconds: Long) {
        position = milliseconds
        reportPosition(milliseconds)
    }
    fun stop() {
        work?.cancel()
        work = null
        engine?.let { it.close(); CapturePlaybackRegistry.release(it) }
        engine = null
    }
    LaunchedEffect(viewer, recordId, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            stop()
            playlist = null
            state = MediaPlaybackState.Loading
            try {
                val result = load()
                check(allowed() && result.recordId == recordId)
                playlist = result
                setPosition(position.coerceIn(0, result.endMs))
                state = MediaPlaybackState.Ready
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) {
                Log.w("CapturePlayback", "Playlist unavailable: reason=${playbackFailureReason(error)}")
                state = MediaPlaybackState.Error; consumeSeek()
            }
            finally {
                stop()
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    playlist = null
                    setPosition(0)
                    consumeSeek()
                }
            }
        }
    }
    DisposableEffect(viewer, recordId) { onDispose { stop() } }
    fun play(milliseconds: Long) {
        val data = playlist ?: return
        if (!allowed() || state == MediaPlaybackState.Error) return
        stop()
        setPosition(milliseconds.coerceIn(0, data.endMs))
        if (data.locate(position) == null) { state = MediaPlaybackState.Gap; return }
        state = MediaPlaybackState.Preparing
        val current = createEngine(allowed)
        engine = current
        CapturePlaybackRegistry.activate(current)
        work = scope.launch {
            try {
                val end = current.play(data, position, rate, onBuffering = {
                    if (engine === current && allowed()) state = MediaPlaybackState.Preparing
                }) {
                    if (engine === current && allowed()) { setPosition(it); state = MediaPlaybackState.Playing }
                }
                if (engine === current && allowed()) { setPosition(end.positionMs); state = if (end.gap) MediaPlaybackState.Gap else MediaPlaybackState.Ready }
            } catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) {
                Log.w("CapturePlayback", "Playback stopped: reason=${playbackFailureReason(error)}")
                if (engine === current) { playlist = null; state = MediaPlaybackState.Error }
            }
            finally {
                current.close()
                CapturePlaybackRegistry.release(current)
                if (engine === current) { engine = null; work = null }
            }
        }
    }
    LaunchedEffect(seek?.token, playlist, state == MediaPlaybackState.Error) {
        val request = seek ?: return@LaunchedEffect
        if (state == MediaPlaybackState.Error) consumeSeek()
        else if (playlist != null && allowed()) { play(request.milliseconds); consumeSeek() }
    }
    val data = playlist
    RecordPlayerSurface {
        if (state.showsSpinner) WeMeetInlineLoading()
        if (state == MediaPlaybackState.Error) WeMeetInlineErrorState(
            onRetry = { refresh++ }, message = stringResource(R.string.capture_playback_error))
        if (data != null) {
            if (data.manifest.outcome == "incomplete") Text(
                stringResource(R.string.capture_playback_incomplete), style = MaterialTheme.typography.bodySmall)
            if (data.chunks.isEmpty()) WeMeetInlineEmptyState(stringResource(R.string.capture_playback_empty))
            else {
                RecordPlaybackControls(
                    positionMs = position, durationMs = data.endMs, playing = state.showsPause, rate = rate,
                    onSeek = { stop(); setPosition(it); state = MediaPlaybackState.Ready; consumeSeek() },
                    onPlayPause = {
                        if (state.showsPause) { stop(); state = MediaPlaybackState.Ready; consumeSeek() }
                        else play(if (position >= data.endMs) 0 else position)
                    },
                    onSkipBack = { play(maxOf(0, position - 15_000)) },
                    onSkipForward = { play(minOf((data.endMs - 1).coerceAtLeast(0), position + 15_000)) },
                    onRate = { speed ->
                        val resume = state.showsPause
                        rate = speed
                        if (resume) play(position)
                    },
                    followState = followState,
                )
                if (state == MediaPlaybackState.Gap) {
                    Text(stringResource(R.string.capture_playback_gap), style = MaterialTheme.typography.bodySmall)
                    data.chunks.firstOrNull { it.startMs >= position }?.let { next ->
                        TextButton(onClick = { play(next.startMs) }) { Text(stringResource(R.string.capture_playback_skip)) }
                    }
                }
            }
        }
    }
}
