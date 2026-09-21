package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.we.meet.ui.theme.Dimens
import java.util.UUID
import kotlinx.coroutines.*

internal data class CaptureAudioSeek(val milliseconds: Long, val token: String = UUID.randomUUID().toString())

@Composable
internal fun NativeCaptureAudioPlayer(viewer: String, recordId: String, repository: CapturePlaybackRepository, currentViewer: () -> String?, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}, onPosition: (Long) -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    CaptureAudioPlayer(viewer, recordId, { repository.playlist(viewer, recordId).getOrThrow() }, { allowed ->
        CapturePlaybackEngine({ playlist, index -> repository.audio(viewer, playlist, index).getOrThrow() },
            { repository.checkAccess(viewer, it).getOrThrow() }, { AndroidCapturePlaybackOutput(context, it) }, allowed)
    }, { currentViewer() == viewer && !CaptureForegroundService.microphoneActive && !ConferenceForegroundService.isRunning }, seek, onSeekConsumed, onPosition)
}

/**
 * Foreground-only player; fixtures inject verified audio and a silent output without real APIs.
 *
 * [onPosition] reports the source-clock position so a transcript can follow playback;
 * it fires on seeks and on every progress tick while playing.
 */
@Composable
internal fun CaptureAudioPlayer(viewer: String, recordId: String, load: suspend () -> CapturePlaylist, createEngine: (allowed: () -> Boolean) -> CapturePlaybackEngine,
    authorized: () -> Boolean, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}, onPosition: (Long) -> Unit = {}) {
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
    var ratesVisible by remember(viewer, recordId) { mutableStateOf(false) }
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
                    ratesVisible = false
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
    // 播放键的「是否显示暂停」收在 MediaPlaybackState.showsPause 一处：
    // 它在「准备中」也返回 true，两个播放器因此一致。
    val playing = state.showsPause
    val positionLabel = stringResource(R.string.capture_playback_position)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            // 只有真的还没有来源时才让位给整块转圈；换段 / 拖动（Preparing）保留控件 ——
            // 原先这里连 Preparing 一起挡掉，于是每次拖进度条控件都会闪一下。
            if (state.showsSpinner) WeMeetInlineLoading()
            if (state == MediaPlaybackState.Error) WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.capture_playback_error))
            if (data != null) {
                if (data.manifest.outcome == "incomplete") Text(stringResource(R.string.capture_playback_incomplete), style = MaterialTheme.typography.bodySmall)
                if (data.chunks.isEmpty()) WeMeetInlineEmptyState(stringResource(R.string.capture_playback_empty))
                else {
                    Text("${sourceTime(position)} / ${sourceTime(data.endMs)}", style = MaterialTheme.typography.labelMedium)
                    Slider(position.coerceAtMost(maxOf(1, data.endMs - 1)).toFloat(), onValueChange = { stop(); setPosition(it.toLong()); state = MediaPlaybackState.Ready; consumeSeek() },
                        valueRange = 0f..maxOf(1, data.endMs - 1).toFloat(), modifier = Modifier.semantics { contentDescription = positionLabel })
                    if (state == MediaPlaybackState.Gap) Text(stringResource(R.string.capture_playback_gap), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { stop(); state = MediaPlaybackState.Ready; consumeSeek(); ratesVisible = true }) { Text(stringResource(R.string.capture_playback_rate, rate)) }
                        IconButton(onClick = { play(maxOf(0, position - 15_000)) }) { Icon(Icons.Outlined.Replay, stringResource(R.string.cd_records_skip_back)) }
                        FilledTonalIconButton(modifier = Modifier.size(Dimens.ButtonHeight), onClick = { if (playing) { stop(); state = MediaPlaybackState.Ready; consumeSeek() } else play(if (position >= data.endMs) 0 else position) }) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(if (playing) R.string.cd_records_pause else R.string.cd_records_play), Modifier.size(Dimens.IconXl))
                        }
                        IconButton(onClick = { play(minOf(data.endMs - 1, position + 15_000)) }) { Icon(Icons.Outlined.FastForward, stringResource(R.string.cd_records_skip_forward)) }
                        if (state == MediaPlaybackState.Gap) data.chunks.firstOrNull { it.startMs >= position }?.let { next ->
                            TextButton(onClick = { play(next.startMs) }) { Text(stringResource(R.string.capture_playback_skip)) }
                        }
                    }
                }
            }
        }
    }
    if (ratesVisible) AlertDialog(onDismissRequest = { ratesVisible = false }, title = { Text(stringResource(R.string.capture_playback_speed)) }, text = {
        Column { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed -> TextButton(onClick = { rate = speed; ratesVisible = false }) { Text(stringResource(R.string.capture_playback_rate, speed)) } } }
    }, confirmButton = { TextButton(onClick = { ratesVisible = false }) { Text(stringResource(R.string.records_close)) } })
}
