package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.we.meet.data.repository.CapturePlaybackRepository
import com.we.meet.data.repository.CapturePlaylist
import com.we.meet.service.CaptureForegroundService
import com.we.meet.service.ConferenceForegroundService
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import java.util.UUID
import kotlinx.coroutines.*

internal data class CaptureAudioSeek(val milliseconds: Long, val token: String = UUID.randomUUID().toString())

@Composable
internal fun NativeCaptureAudioPlayer(viewer: String, recordId: String, repository: CapturePlaybackRepository, currentViewer: () -> String?, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    CaptureAudioPlayer(viewer, recordId, { repository.playlist(viewer, recordId).getOrThrow() }, { allowed ->
        CapturePlaybackEngine({ playlist, index -> repository.audio(viewer, playlist, index).getOrThrow() },
            { repository.checkAccess(viewer, it).getOrThrow() }, { AndroidCapturePlaybackOutput(context, it) }, allowed)
    }, { currentViewer() == viewer && !CaptureForegroundService.microphoneActive && !ConferenceForegroundService.isRunning }, seek, onSeekConsumed)
}

/** Foreground-only player; fixtures inject verified audio and a silent output without real APIs. */
@Composable
internal fun CaptureAudioPlayer(viewer: String, recordId: String, load: suspend () -> CapturePlaylist, createEngine: (allowed: () -> Boolean) -> CapturePlaybackEngine,
    authorized: () -> Boolean, seek: CaptureAudioSeek? = null, onSeekConsumed: () -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentAllowed by rememberUpdatedState(authorized)
    val consumeSeek by rememberUpdatedState(onSeekConsumed)
    var playlist by remember(viewer, recordId) { mutableStateOf<CapturePlaylist?>(null) }
    var state by remember(viewer, recordId) { mutableStateOf("loading") }
    var position by remember(viewer, recordId) { mutableLongStateOf(0) }
    var rate by remember(viewer, recordId) { mutableFloatStateOf(1f) }
    var refresh by remember(viewer, recordId) { mutableIntStateOf(0) }
    var engine by remember(viewer, recordId) { mutableStateOf<CapturePlaybackEngine?>(null) }
    var work by remember(viewer, recordId) { mutableStateOf<Job?>(null) }
    var ratesVisible by remember(viewer, recordId) { mutableStateOf(false) }
    val allowed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentAllowed() }
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
            state = "loading"
            try {
                val result = load()
                check(allowed() && result.recordId == recordId)
                playlist = result
                position = 0
                state = "ready"
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state = "error"; consumeSeek() }
            finally {
                stop()
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    playlist = null
                    position = 0
                    ratesVisible = false
                    consumeSeek()
                }
            }
        }
    }
    DisposableEffect(viewer, recordId) { onDispose { stop() } }
    fun play(milliseconds: Long) {
        val data = playlist ?: return
        if (!allowed() || state == "error") return
        stop()
        position = milliseconds.coerceIn(0, data.endMs)
        if (data.locate(position) == null) { state = "gap"; return }
        state = "buffering"
        val current = createEngine(allowed)
        engine = current
        CapturePlaybackRegistry.activate(current)
        work = scope.launch {
            try {
                val end = current.play(data, position, rate) {
                    if (engine === current && allowed()) { position = it; state = "playing" }
                }
                if (engine === current && allowed()) { position = end.positionMs; state = if (end.gap) "gap" else "ready" }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { if (engine === current) { playlist = null; state = "error" } }
            finally {
                current.close()
                CapturePlaybackRegistry.release(current)
                if (engine === current) { engine = null; work = null }
            }
        }
    }
    LaunchedEffect(seek?.token, playlist, state == "error") {
        val request = seek ?: return@LaunchedEffect
        if (state == "error") consumeSeek()
        else if (playlist != null && allowed()) { play(request.milliseconds); consumeSeek() }
    }
    val data = playlist
    val playing = state in setOf("playing", "buffering")
    val positionLabel = stringResource(R.string.capture_playback_position)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.capture_playback_title), style = MaterialTheme.typography.titleSmall)
            if (state == "loading" || state == "buffering") WeMeetInlineLoading()
            if (state == "error") WeMeetInlineErrorState(onRetry = { refresh++ }, message = stringResource(R.string.capture_playback_error))
            if (data != null) {
                if (data.manifest.outcome == "incomplete") Text(stringResource(R.string.capture_playback_incomplete), style = MaterialTheme.typography.bodySmall)
                if (data.chunks.isEmpty()) Text(stringResource(R.string.capture_playback_empty))
                else {
                    Text("${sourceTime(position)} / ${sourceTime(data.endMs)}", style = MaterialTheme.typography.labelMedium)
                    Slider(position.coerceAtMost(maxOf(1, data.endMs - 1)).toFloat(), onValueChange = { stop(); position = it.toLong(); state = "ready"; consumeSeek() },
                        valueRange = 0f..maxOf(1, data.endMs - 1).toFloat(), modifier = Modifier.semantics { contentDescription = positionLabel })
                    if (state == "gap") Text(stringResource(R.string.capture_playback_gap), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        TextButton(onClick = { if (playing) { stop(); state = "ready"; consumeSeek() } else play(if (position >= data.endMs) 0 else position) }) {
                            Text(stringResource(if (playing) R.string.capture_playback_pause else R.string.capture_playback_play))
                        }
                        TextButton(onClick = { stop(); state = "ready"; consumeSeek(); ratesVisible = true }) { Text(stringResource(R.string.capture_playback_rate, rate)) }
                        if (state == "gap") data.chunks.firstOrNull { it.startMs >= position }?.let { next ->
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
