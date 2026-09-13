@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.capture.CaptureRetention
import com.we.meet.service.CaptureForegroundService
import com.we.meet.service.CaptureServiceHost
import com.we.meet.service.CaptureServiceState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

private fun bindCapture(context: Context) = callbackFlow {
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? CaptureForegroundService.LocalBinder)?.service
            if (service == null) close(IllegalStateException("Recording service unavailable")) else trySend(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) { close(IllegalStateException("Recording service disconnected")) }
        override fun onNullBinding(name: ComponentName?) { close(IllegalStateException("Recording service unavailable")) }
        override fun onBindingDied(name: ComponentName?) { close(IllegalStateException("Recording service disconnected")) }
    }
    val bound = context.bindService(CaptureForegroundService.bindingIntent(context), connection, Context.BIND_AUTO_CREATE)
    if (!bound) close(IllegalStateException("Recording service unavailable"))
    awaitClose { if (bound) context.unbindService(connection) }
}

private fun Context.captureActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.captureActivity()
    else -> null
}

private data class CaptureLaunch(val title: String, val retentionMode: String)

@Composable
fun CaptureScreen(viewer: String, onBack: () -> Unit, onRecord: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var retry by remember(viewer) { mutableIntStateOf(0) }
    var service by remember(viewer) { mutableStateOf<CaptureForegroundService?>(null) }
    var state by remember(viewer) { mutableStateOf(CaptureServiceState()) }
    var permissionError by remember(viewer) { mutableStateOf(false) }
    var requestedTitle by remember(viewer) { mutableStateOf<CaptureLaunch?>(null) }
    var launchTitle by remember(viewer) { mutableStateOf<CaptureLaunch?>(null) }
    val admission = visibleRead(viewer, retry) {
        (context.applicationContext as CaptureServiceHost).captureRepository.textAudioAvailable(viewer)
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionError = !granted
        if (granted) launchTitle = requestedTitle
        requestedTitle = null
    }
    LaunchedEffect(viewer, retry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                check(BuildConfig.WE_MEET_CAPTURE_NATIVE && viewer.isNotBlank())
                bindCapture(context.applicationContext).collectLatest { bound ->
                    service = bound
                    bound.state.collect { value ->
                        state = if (value.viewer == viewer || value.viewer == null) value else CaptureServiceState(error = true)
                    }
                }
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state = CaptureServiceState(error = true) }
            finally { service = null; if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state = CaptureServiceState() }
        }
    }
    LaunchedEffect(launchTitle, viewer, lifecycle) {
        val title = launchTitle ?: return@LaunchedEffect
        lifecycle.withResumed {
            permissionError = runCatching {
                check((context.applicationContext as? CaptureServiceHost)?.captureAccount == viewer)
                CaptureForegroundService.start(requireNotNull(context.captureActivity()), title.title, title.retentionMode)
            }.isFailure
            launchTitle = null
        }
    }
    fun requestStart(title: String, retentionMode: String) {
        permissionError = false
        val request = CaptureLaunch(title, retentionMode)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchTitle = request
        else { requestedTitle = request; microphone.launch(Manifest.permission.RECORD_AUDIO) }
    }
    CaptureContent(state, permissionError, onBack, onStart = { requestStart(it, "media") },
        onStartText = if (admission?.getOrNull() == true) ({ requestStart(it, "text") }) else null,
        onFinishIncomplete = { service?.finish(allowMissing = true) },
        onPause = { service?.pause() }, onFinish = { service?.finish() },
        onRetry = { if (state.ready) service?.retryUploads() else retry++ },
        onRecord = if (BuildConfig.WE_MEET_RECORDS_NATIVE) onRecord else null,
        notificationsAvailable = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        extra = {
            val app = context.applicationContext as? WeMeetApp
            val capture = state.local?.remote
            if (app != null && capture != null && state.viewer == viewer) {
                androidx.compose.runtime.key(viewer, capture.id) {
                    var audioSeek by remember { mutableStateOf<CaptureAudioSeek?>(null) }
                    var summariesSelected by remember { mutableStateOf(capture.status in setOf("stopping", "stopped")) }
                    LaunchedEffect(capture.status) { if (capture.status in setOf("stopping", "stopped")) summariesSelected = true }
                    val textMode = state.local?.create?.retentionMode == "text"
                    if (textMode) CaptureRetentionPanel(viewer, capture, app.captureTranscriptionRepository)
                    if (capture.status == "stopped" && !textMode) NativeCaptureAudioPlayer(viewer, capture.recordId, app.capturePlaybackRepository, { app.captureAccount }, audioSeek) { audioSeek = null }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        androidx.compose.material3.FilterChip(!summariesSelected, { summariesSelected = false }, label = { Text(stringResource(R.string.records_originals)) })
                        androidx.compose.material3.FilterChip(summariesSelected, { summariesSelected = true }, label = { Text(stringResource(R.string.records_minutes)) })
                    }
                    if (!summariesSelected) CaptureAsrPanel(viewer, capture, app.captureTranscriptionRepository) { app.captureAccount }
                    else CaptureSummaryWorkspace(viewer, capture, app.meetingRecordRepository, app.meetingSummaryRepository,
                        { app.captureAccount }, if (BuildConfig.WE_MEET_RECORDS_NATIVE) ({ onRecord(capture.recordId) }) else null,
                        if (capture.status == "stopped" && !textMode) ({ audioSeek = CaptureAudioSeek(it) }) else null)
                }
            }
        })
}

/** Pure rendering boundary, usable with fixture states without starting a service or microphone. */
@Composable
internal fun CaptureContent(
    state: CaptureServiceState,
    permissionError: Boolean,
    onBack: () -> Unit,
    onStart: (String) -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onRecord: ((String) -> Unit)?,
    notificationsAvailable: Boolean = true,
    onStartText: ((String) -> Unit)? = null,
    onFinishIncomplete: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    var title by remember(state.viewer, state.local?.id) { mutableStateOf("") }
    var confirmEnd by remember(state.viewer, state.local?.id) { mutableStateOf(false) }
    var confirmIncomplete by remember(state.viewer, state.local?.id) { mutableStateOf(false) }
    var textOnly by remember(state.viewer, state.local?.id) { mutableStateOf(false) }
    val local = state.local
    val textMode = if (local != null && !local.sealed) local.create.retentionMode == "text" else textOnly
    fun start() { if (textOnly && (local == null || local.sealed)) onStartText?.invoke(title) else onStart(title) }
    val ending = local != null && !local.sealed && (local.sealIntent != null || local.remote?.status in setOf("stopping", "stopped"))
    val canStart = state.ready && !state.busy && !state.recording &&
        (local == null || local.sealed || !CaptureRetention.audioExpired(local)) &&
        (!textOnly || onStartText != null) &&
        (local == null || local.sealed || (local.sealIntent == null && local.remote?.status !in setOf("stopping", "stopped")))
    val status = when {
        state.recording -> R.string.capture_status_recording
        state.busy -> R.string.capture_status_working
        local?.sealed == true -> if (local.create.retentionMode == "text") R.string.capture_text_saved else R.string.capture_status_saved
        ending -> R.string.capture_status_finishing
        local?.interrupted == true -> R.string.capture_status_interrupted
        local != null -> R.string.capture_status_paused
        else -> R.string.capture_status_ready
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { WeMeetTopBar(title = stringResource(R.string.capture_notification_title), onBack = onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
            if (!state.ready && !state.error) WeMeetInlineLoading()
            if (state.error || permissionError) {
                WeMeetInlineErrorState(message = stringResource(if (permissionError) R.string.capture_permission_error else R.string.capture_operation_error),
                    onRetry = if (permissionError) ({ start() }) else onRetry)
            }
            if (state.retentionExpired) Text(stringResource(R.string.capture_text_expired), color = MaterialTheme.colorScheme.error)
            if (state.ready) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        Text(stringResource(status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (local == null || local.sealed) {
                            OutlinedTextField(title, { title = it.take(500) }, modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.capture_title_label)) }, singleLine = true, enabled = !state.busy)
                        } else Text(local.create.title.ifBlank { stringResource(R.string.capture_untitled) }, style = MaterialTheme.typography.titleLarge)
                        if ((local == null || local.sealed) && (onStartText != null || textOnly)) Row {
                            Checkbox(textOnly, { textOnly = it }, enabled = !state.busy)
                            Text(stringResource(R.string.capture_text_only), Modifier.padding(top = Dimens.SpaceM))
                        }
                        if (textMode) Text(stringResource(R.string.capture_text_consent), style = MaterialTheme.typography.bodyMedium)
                        if (textOnly && onStartText == null) Text(stringResource(R.string.capture_text_unavailable), color = MaterialTheme.colorScheme.error)
                        val seconds = (local?.durationMs ?: 0L) / 1000
                        Text(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
                            style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.capture_saved_duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.recording) Text(stringResource(if (notificationsAvailable) R.string.capture_background_hint else R.string.capture_background_without_notifications), style = MaterialTheme.typography.bodyMedium)
                        else if (ending) Text(stringResource(R.string.capture_finish_pending_hint), style = MaterialTheme.typography.bodyMedium)
                        else if (local?.interrupted == true && !local.sealed) Text(stringResource(if (textMode) R.string.capture_text_interrupted_hint else R.string.capture_interrupted_hint), style = MaterialTheme.typography.bodyMedium)
                        else if (local == null && !textMode) Text(stringResource(R.string.capture_intro), style = MaterialTheme.typography.bodyMedium)
                        if (state.busy) WeMeetInlineLoading()
                        if (state.recording) OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_pause)) }
                        else if (!ending) Button(onClick = { start() }, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (local == null || local.sealed) R.string.capture_start else R.string.capture_resume))
                        }
                        if (local != null && !local.sealed) OutlinedButton(onClick = { if (ending) onFinish() else confirmEnd = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (ending) R.string.capture_complete_save else R.string.capture_finish))
                        }
                        if (local?.create?.retentionMode == "text" && !local.sealed && !state.recording && !state.busy &&
                            (local.interrupted || state.error || state.uploadFailed || state.retentionExpired) && onFinishIncomplete != null) {
                            TextButton(onClick = { confirmIncomplete = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.capture_text_finish_incomplete))
                            }
                        }
                    }
                }
                if (local != null) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        Text(stringResource(if (local.create.retentionMode == "text") R.string.capture_text_local_hint else if (local.sealed) R.string.capture_saved_hint else R.string.capture_local_hint), style = MaterialTheme.typography.bodyMedium)
                        if (local.pendingBytes > 0 || state.uploadFailed) {
                            Text(stringResource(R.string.capture_pending_count, local.pendingBytes / 1024), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.capture_retry_uploads)) }
                        }
                        if (local.sealed && onRecord != null && local.remote != null) TextButton(onClick = { onRecord(local.remote.recordId) }) {
                            Text(stringResource(R.string.capture_open_record))
                        }
                    }
                }
                extra()
            }
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text(stringResource(R.string.capture_finish)) }, text = { Text(stringResource(if (textMode) R.string.capture_text_finish_hint else R.string.capture_finish_hint)) },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onFinish() }) { Text(stringResource(R.string.capture_finish_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.capture_keep_recording)) } })
    if (confirmIncomplete) AlertDialog(onDismissRequest = { confirmIncomplete = false },
        title = { Text(stringResource(R.string.capture_text_finish_incomplete)) },
        text = { Text(stringResource(R.string.capture_text_incomplete_hint)) },
        confirmButton = { TextButton(onClick = { confirmIncomplete = false; onFinishIncomplete?.invoke() }) { Text(stringResource(R.string.capture_text_confirm_incomplete)) } },
        dismissButton = { TextButton(onClick = { confirmIncomplete = false }) { Text(stringResource(R.string.capture_keep_recording)) } })
}
