@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.voiceprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.AndroidCapturePcmSource
import com.we.meet.data.repository.*
import com.we.meet.data.voiceprint.*
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

@Composable
fun VoiceprintSettingsScreen(repository: VoiceprintRepository, viewer: String, onBack: () -> Unit,
    recordingFactory: (Context) -> VoiceprintRecording = { context -> VoiceprintRecorder { AndroidCapturePcmSource.open(context, VoiceprintWave.SAMPLE_RATE) } }) {
    val client = remember(repository, viewer) { runCatching { repository.open(viewer) }.getOrNull() }
    var allowed by remember(client) { mutableStateOf(client?.allowed() == true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (client?.allowed() == true) delay(250)
            allowed = false
        }
    }
    Scaffold(topBar = { WeMeetTopBar(stringResource(R.string.voiceprint_title), onBack = onBack) }) { padding ->
        if (client == null || !allowed) Text(stringResource(R.string.voiceprint_login_required), Modifier.padding(padding).padding(Dimens.ScreenPadding))
        else ScopeChooser(repository, viewer, client, Modifier.padding(padding), recordingFactory)
    }
}

@Composable
private fun ScopeChooser(repository: VoiceprintRepository, viewer: String, client: VoiceprintSession, modifier: Modifier, recordingFactory: (Context) -> VoiceprintRecording) {
    var scopes by remember(client) { mutableStateOf<List<VoiceprintScopeDto>>(emptyList()) }
    var next by remember(client) { mutableStateOf<Int?>(null) }
    var selected by remember(client) { mutableStateOf<String?>(null) }
    var failed by remember(client) { mutableStateOf(false) }
    var busy by remember(client) { mutableStateOf(false) }
    var sequence by remember(client) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun load(offset: Int) {
        if (!client.allowed() || busy) return
        val request = ++sequence; busy = true
        try {
            val page = client.scopes(offset).getOrThrow()
            if (client.allowed() && request == sequence) {
                val previous = scopes.find { it.id == selected }
                scopes = if (offset == 0) page.results else (scopes + page.results).distinctBy { it.id }
                if (selected != null && scopes.none { it.id == selected } && previous != null) scopes = scopes + previous.copy(canManagePolicy = false)
                next = page.nextOffset; failed = false
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (client.allowed() && request == sequence) failed = true }
        finally { if (request == sequence) busy = false }
    }
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { load(0); kotlinx.coroutines.awaitCancellation() }
            finally { sequence++; scopes = emptyList(); next = null; busy = false; failed = false }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(R.string.voiceprint_intro))
        Text(stringResource(R.string.voiceprint_scope), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            FilterChip(selected == null, onClick = { selected = null }, label = { Text(stringResource(R.string.voiceprint_personal)) })
            scopes.forEach { bank -> FilterChip(selected == bank.id, onClick = { selected = bank.id }, label = { Text(bank.name) }) }
        }
        if (failed) Text(stringResource(R.string.voiceprint_scope_error), color = MaterialTheme.colorScheme.error)
        if (next != null || failed) TextButton(enabled = !busy, onClick = { scope.launch { load(if (failed) 0 else next ?: return@launch) } }) { Text(stringResource(if (failed) R.string.voiceprint_reload else R.string.voiceprint_more_scopes)) }
        key(client, selected) {
            val current = remember(repository, viewer, selected) { repository.open(viewer, selected) }
            ScopeBody(current, scopes.find { it.id == selected }, onPolicyChanged = { scope.launch { load(0) } }, recordingFactory)
        }
    }
}

@Composable
private fun ScopeBody(client: VoiceprintSession, policyScope: VoiceprintScopeDto?, onPolicyChanged: () -> Unit, recordingFactory: (Context) -> VoiceprintRecording) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var playing by remember(client) { mutableStateOf<String?>(null) }
    val output = remember(client) { VoiceprintAudioOutput(context) { playing = null } }
    val controller = remember(client) { VoiceprintController(SessionVoiceprintOperations(client), { output.stop(); playing = null }) }
    val state by controller.state.collectAsState()
    var fileTarget by remember { mutableStateOf<VoiceprintExternalAction?>(null) }
    var permissionTarget by remember { mutableStateOf<VoiceprintExternalAction?>(null) }
    var localError by remember { mutableStateOf<VoiceprintFailure?>(null) }
    val startRecording: (VoiceprintController) -> Unit = { target ->
        if (target.allowed() && target.canRecord() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            scope.launch { target.record(recordingFactory(context)) }
        }
    }
    suspend fun restore(action: VoiceprintExternalAction): Boolean {
        lifecycle.withResumed { }
        if (!controller.allowed()) return false
        controller.resume()
        return withTimeout(20000) {
            controller.state.first { !it.busy }
            controller.restoreExternalAction(action)
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = permissionTarget; permissionTarget = null
        if (action != null) scope.launch {
            try {
                if (restore(action)) {
                    if (granted) startRecording(controller) else localError = VoiceprintFailure.MICROPHONE
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (controller.allowed()) localError = VoiceprintFailure.REQUEST }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val action = fileTarget; fileTarget = null
        if (uri != null && action != null) scope.launch {
            try {
                if (!restore(action)) return@launch
                val bytes = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use(VoiceprintWave::read) }
                try { controller.file(bytes) } finally { bytes.fill(0) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (controller.allowed()) localError = if (error is VoiceprintDurationException) VoiceprintFailure.DURATION else VoiceprintFailure.FORMAT }
        }
    }
    DisposableEffect(controller) { onDispose { permissionTarget = null; fileTarget = null; controller.close(); output.stop() } }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.resume()
            try {
                controller.refresh()
                launch { while (true) { delay(5000); controller.refresh() } }
                while (controller.allowed()) {
                    controller.tick()
                    if (playing != null && output.completed()) {
                        val id = playing; output.stop(); playing = null
                        if (id != null && id != "local") controller.listened(id)
                    }
                    delay(100)
                }
            } finally { controller.background(); localError = null }
        }
    }
    val enabled = !state.busy && !state.conflict && state.settings != null
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val language = configuration.locales[0]?.language ?: "en"
    val locale = if (language == "zh") "zh-CN" else language.takeIf { it in listOf("fr", "de", "nl") } ?: "en"
    fun play(bytes: ByteArray, id: String) {
        if (!client.allowed() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        try { output.play(bytes); playing = id; localError = null }
        catch (_: Exception) { playing = null; localError = VoiceprintFailure.REQUEST }
    }
    if (state.busy) WeMeetInlineLoading()
    (localError ?: state.failure)?.let { Text(stringResource(failureText(it)), color = MaterialTheme.colorScheme.error) }
    TextButton(enabled = !state.busy, onClick = { scope.launch { localError = null; controller.refresh(true) } }) { Text(stringResource(R.string.voiceprint_reload)) }
    state.settings?.let { settings ->
        if (!settings.available) Text(stringResource(R.string.voiceprint_disabled))
        if (policyScope?.canManagePolicy == true) {
            PermissionRow(stringResource(R.string.voiceprint_organization_policy), stringResource(R.string.voiceprint_organization_policy_hint), policyScope.policy.enabled, enabled) {
                scope.launch { controller.policy(it, policyScope.policy.version); onPolicyChanged() }
            }
        }
        VoiceprintPermission.entries.forEach { permission ->
            val (title, hint) = permissionText(permission)
            PermissionRow(stringResource(title), stringResource(hint), permission.enabled(settings), enabled && (settings.available || permission.enabled(settings))) { scope.launch { controller.change(permission, it) } }
        }
        Text(stringResource(R.string.voiceprint_enrollment_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.voiceprint_enrollment_hint))
        if (state.enrollment == null) TextButton(enabled = enabled && settings.available && settings.allowEnrollment, onClick = { scope.launch { controller.begin(locale) } }) { Text(stringResource(R.string.voiceprint_begin)) }
        state.enrollment?.let { enrollment ->
            Text(stringResource(R.string.voiceprint_enrollment_progress, enrollment.uploadedSlots.size, enrollment.maxClips))
            Text(stringResource(R.string.voiceprint_enrollment_expires, enrollment.expiresAt))
            controller.slot().takeIf { it in 0..5 }?.let { Text(enrollment.challenges[it], style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(when (state.phase) { VoiceprintPhase.IDLE -> R.string.voiceprint_recording_idle; VoiceprintPhase.REQUESTING -> R.string.voiceprint_recording_requesting; VoiceprintPhase.RECORDING -> R.string.voiceprint_recording_recording }, state.seconds))
            FlowRow {
                TextButton(enabled = enabled && controller.canRecord(), onClick = {
                    localError = null
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording(controller)
                    else { permissionTarget = controller.externalAction(); if (permissionTarget != null) microphone.launch(Manifest.permission.RECORD_AUDIO) }
                }) { Text(stringResource(R.string.voiceprint_record)) }
                if (state.phase != VoiceprintPhase.IDLE) {
                    TextButton(onClick = controller::finishRecording) { Text(stringResource(R.string.voiceprint_stop)) }
                    TextButton(onClick = controller::cancelRecording) { Text(stringResource(R.string.voiceprint_cancel_recording)) }
                }
                TextButton(enabled = !state.busy, onClick = controller::endEnrollment) { Text(stringResource(R.string.voiceprint_end_enrollment)) }
            }
            Text(stringResource(R.string.voiceprint_file_hint))
            TextButton(enabled = enabled && controller.canRecord(), onClick = { fileTarget = controller.externalAction(); if (fileTarget != null) picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave")) }) { Text(stringResource(R.string.voiceprint_file)) }
            state.clip?.let { bytes ->
                TextButton(enabled = !state.busy, onClick = { play(bytes, "local") }) { Text(stringResource(R.string.voiceprint_local_audio)) }
                TextButton(enabled = enabled && controller.canUpload(), onClick = { scope.launch { controller.upload() } }) { Text(stringResource(R.string.voiceprint_upload)) }
                TextButton(enabled = !state.busy, onClick = controller::discard) { Text(stringResource(R.string.voiceprint_discard)) }
            }
        }
        Text(stringResource(R.string.voiceprint_profiles), style = MaterialTheme.typography.titleLarge)
        Text(if (settings.displayState != null) stringResource(R.string.voiceprint_scope_state, stringResource(displayStateText(settings.displayState))) else stringResource(R.string.voiceprint_status_unavailable))
        if (settings.profiles.isEmpty()) Text(stringResource(R.string.voiceprint_no_profile))
        settings.profiles.forEach { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(Dimens.ScreenPadding)) {
                Text(stringResource(if (profile.displayState != null) displayStateText(profile.displayState) else if (profile.status == "active") R.string.voiceprint_display_state_needs_update else profileText(profile.status)))
                profile.updateReasons.forEach { reason -> Text(stringResource(updateReasonText(reason))) }
                Text(stringResource(R.string.voiceprint_effective_groups, stringResource(if (profile.effectiveDeviceGroups.isNotEmpty()) R.string.voiceprint_default_group else R.string.voiceprint_no_effective_groups)))
                Text(stringResource(R.string.voiceprint_last_confirmed, profile.confirmedAt ?: stringResource(R.string.voiceprint_never)))
                Text(stringResource(R.string.voiceprint_last_updated, profile.lastUpdatedAt ?: stringResource(R.string.voiceprint_never)))
                if (profile.status != "deleted") TextButton(enabled = enabled, onClick = { controller.requestRemoval(profile.id) }) { Text(stringResource(R.string.voiceprint_delete)) }
            } }
        }
        Text(stringResource(R.string.voiceprint_samples), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.voiceprint_quality_hint))
        if (state.samples.isEmpty()) Text(stringResource(R.string.voiceprint_no_samples))
        state.samples.forEachIndexed { index, sample ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(stringResource(R.string.voiceprint_sample_title, index + 1, (sample.durationMs / 1000).toInt()))
                Text(stringResource(sampleText(sample.status)))
                Text(stringResource(R.string.voiceprint_audio_expires, sample.expiresAt))
                if (sample.audioAvailable) {
                    TextButton(enabled = enabled, onClick = { scope.launch { controller.preview(sample) } }) { Text(stringResource(R.string.voiceprint_listen)) }
                    state.preview?.takeIf { it.sampleId == sample.id }?.let { preview ->
                        TextButton(enabled = enabled, onClick = { play(preview.bytes, sample.id) }) { Text(stringResource(R.string.voiceprint_sample_audio)) }
                        Text(stringResource(R.string.voiceprint_listen_hint))
                        Row(Modifier.fillMaxWidth().toggleable(preview.selfConfirmed, enabled = enabled, role = Role.Checkbox, onValueChange = { controller.confirmSelf(sample.id, it) })) {
                            Checkbox(preview.selfConfirmed, onCheckedChange = null); Text(stringResource(R.string.voiceprint_self_confirmed), Modifier.weight(1f))
                        }
                        if (sample.confirmable) TextButton(enabled = enabled && preview.listened && preview.selfConfirmed, onClick = { scope.launch { controller.decide(sample, true) } }) { Text(stringResource(R.string.voiceprint_confirm)) }
                    }
                    if (sample.status in listOf("pending", "processing", "quality_pending", "ready")) TextButton(enabled = enabled && settings.allowEnrollment, onClick = { scope.launch { controller.decide(sample, false) } }) { Text(stringResource(R.string.voiceprint_reject)) }
                }
            } }
        }
        if (state.sampleOffset != null) TextButton(enabled = enabled, onClick = { scope.launch { controller.more(true) } }) { Text(stringResource(R.string.voiceprint_more)) }
        Text(stringResource(R.string.voiceprint_deletions), style = MaterialTheme.typography.titleLarge)
        state.deletions.forEach { Text(stringResource(deletionText(it.status))) }
        if (state.deletionOffset != null) TextButton(enabled = enabled, onClick = { scope.launch { controller.more(false) } }) { Text(stringResource(R.string.voiceprint_more)) }
    }
    state.deleteTarget?.let {
        AlertDialog(onDismissRequest = { if (!state.busy) controller.requestRemoval(null) }, title = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) { Text(stringResource(R.string.voiceprint_delete)) } },
            text = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) { Text(stringResource(R.string.voiceprint_delete_confirmation, policyScope?.name ?: stringResource(R.string.voiceprint_personal))) } },
            confirmButton = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) { TextButton(enabled = enabled, onClick = { scope.launch { controller.remove() } }) { Text(stringResource(R.string.voiceprint_confirm_delete)) } } },
            dismissButton = { CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) { TextButton(enabled = !state.busy, onClick = { controller.requestRemoval(null) }) { Text(stringResource(R.string.voiceprint_cancel)) } } })
    }
}

private fun displayStateText(state: String) = when (state) {
    "not_enabled" -> R.string.voiceprint_display_state_not_enabled
    "collecting" -> R.string.voiceprint_display_state_collecting
    "awaiting_confirmation" -> R.string.voiceprint_display_state_awaiting_confirmation
    "established" -> R.string.voiceprint_display_state_established
    "needs_update" -> R.string.voiceprint_display_state_needs_update
    "paused" -> R.string.voiceprint_display_state_paused
    "deleting" -> R.string.voiceprint_display_state_deleting
    "deleted" -> R.string.voiceprint_display_state_deleted
    else -> R.string.voiceprint_status_unavailable
}
private fun updateReasonText(reason: String) = when (reason) {
    "expired" -> R.string.voiceprint_update_reason_expired
    "model_changed" -> R.string.voiceprint_update_reason_model_changed
    "contributions_changed" -> R.string.voiceprint_update_reason_contributions_changed
    "storage_unavailable" -> R.string.voiceprint_update_reason_storage_unavailable
    else -> R.string.voiceprint_status_unavailable
}

@Composable private fun PermissionRow(title: String, hint: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(hint, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
private fun permissionText(permission: VoiceprintPermission) = when (permission) {
    VoiceprintPermission.ENROLLMENT -> R.string.voiceprint_permissions_allow_enrollment_title to R.string.voiceprint_permissions_allow_enrollment_hint
    VoiceprintPermission.ACCUMULATION -> R.string.voiceprint_permissions_allow_accumulation_title to R.string.voiceprint_permissions_allow_accumulation_hint
    VoiceprintPermission.IDENTIFICATION -> R.string.voiceprint_permissions_allow_identification_title to R.string.voiceprint_permissions_allow_identification_hint
}
private fun failureText(failure: VoiceprintFailure) = when (failure) {
    VoiceprintFailure.REQUEST -> R.string.voiceprint_errors_failed; VoiceprintFailure.ACCESS -> R.string.voiceprint_errors_unavailable
    VoiceprintFailure.CONFLICT -> R.string.voiceprint_errors_conflict; VoiceprintFailure.MICROPHONE -> R.string.voiceprint_errors_microphone
    VoiceprintFailure.DURATION -> R.string.voiceprint_errors_duration; VoiceprintFailure.FORMAT -> R.string.voiceprint_errors_voiceprint_audio_format_invalid
    VoiceprintFailure.QUOTA -> R.string.voiceprint_errors_voiceprint_enrollment_quota; VoiceprintFailure.DUPLICATE -> R.string.voiceprint_errors_voiceprint_duplicate_audio
    VoiceprintFailure.QUALITY -> R.string.voiceprint_errors_voiceprint_quality_pending; VoiceprintFailure.EXPIRED -> R.string.voiceprint_errors_voiceprint_upload_expired
    VoiceprintFailure.STORAGE -> R.string.voiceprint_errors_voiceprint_key_unavailable
}
private fun profileText(status: String) = when (status) {
    "active" -> R.string.voiceprint_profile_status_active; "paused" -> R.string.voiceprint_profile_status_paused
    "deleted" -> R.string.voiceprint_profile_status_deleted; else -> R.string.voiceprint_profile_status_pending
}
private fun sampleText(status: String) = when (status) {
    "pending" -> R.string.voiceprint_sample_status_pending; "processing" -> R.string.voiceprint_sample_status_processing
    "quality_pending" -> R.string.voiceprint_sample_status_quality_pending; "ready" -> R.string.voiceprint_sample_status_ready
    "confirmed" -> R.string.voiceprint_sample_status_confirmed; "rejected" -> R.string.voiceprint_sample_status_rejected
    "expired" -> R.string.voiceprint_sample_status_expired; else -> R.string.voiceprint_sample_status_deleted
}
private fun deletionText(status: String) = when (status) {
    "queued" -> R.string.voiceprint_deletion_status_queued; "running" -> R.string.voiceprint_deletion_status_running
    "succeeded" -> R.string.voiceprint_deletion_status_succeeded; else -> R.string.voiceprint_deletion_status_failed
}
