package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.AssistantTranscriptionApi
import com.we.meet.data.capture.CapturePcmTap
import com.we.meet.data.capture.DirectAsrRow
import com.we.meet.data.capture.DirectAsrWire
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Foreground validation only; ordinary recording and canonical cloud ASR stay independent. */
@Composable
internal fun CaptureDirectAsrPanel(api: AssistantTranscriptionApi, authorized: () -> Boolean,
    recording: () -> Boolean, observe: () -> CapturePcmTap.Subscription) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val allowed by rememberUpdatedState(authorized)
    val microphone by rememberUpdatedState(recording)
    val tapFactory by rememberUpdatedState(observe)
    var job by remember { mutableStateOf<Job?>(null) }
    var tap by remember { mutableStateOf<CapturePcmTap.Subscription?>(null) }
    var wire by remember { mutableStateOf<DirectAsrWire?>(null) }
    var phase by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<DirectAsrRow>()) }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { awaitCancellation() }
            finally { job?.cancel(); tap?.close(); wire?.close(); job = null; tap = null; wire = null; phase = 0 }
        }
    }
    DisposableEffect(Unit) {
        onDispose { job?.cancel(); tap?.close(); wire?.close() }
    }
    fun start() {
        if (job != null || !allowed() || !microphone() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        rows = emptyList(); phase = 1
        job = scope.launch {
            var input: CapturePcmTap.Subscription? = null
            var socket: DirectAsrWire? = null
            try {
                val credentials = api.session()
                check(allowed() && microphone())
                socket = DirectAsrWire { value -> scope.launch { if (allowed() && phase in 1..3) rows = value } }
                wire = socket
                socket.start(credentials)
                check(allowed() && microphone())
                input = tapFactory(); tap = input; phase = 2
                val source = input
                val connection = socket
                withContext(Dispatchers.IO) {
                    while (isActive) {
                        check(allowed())
                        val frame = source.poll()
                        if (frame != null) frame.use {
                            val bytes = ByteBuffer.allocate(it.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.asShortBuffer().put(it.samples)
                            check(connection.send(bytes.array()))
                        } else when (source.state) {
                            CapturePcmTap.State.FINISHED -> break
                            CapturePcmTap.State.RUNNING -> delay(10)
                            else -> error("ASR source unavailable")
                        }
                    }
                }
                phase = 3
                rows = socket.finish()
                phase = 4
            } catch (cancel: CancellationException) { phase = 0; throw cancel }
            catch (_: Exception) { phase = 5 }
            finally { input?.close(); socket?.close(); tap = null; wire = null; job = null }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(R.string.capture_direct_asr_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.capture_direct_asr_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (phase == 2) { phase = 3; tap?.finish() }
            else if (phase == 1) job?.cancel()
            else start()
        }, enabled = phase in 1..2 || job == null && microphone()) {
            Text(stringResource(if (job != null) R.string.capture_direct_asr_stop else R.string.capture_direct_asr_start))
        }
        if (phase != 0) Text(stringResource(when (phase) {
            1 -> R.string.capture_direct_asr_connecting
            2 -> R.string.capture_direct_asr_listening
            3 -> R.string.capture_direct_asr_finishing
            4 -> R.string.capture_direct_asr_finished
            else -> R.string.capture_direct_asr_failed
        }), color = if (phase == 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        rows.takeLast(20).forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
    }
}
