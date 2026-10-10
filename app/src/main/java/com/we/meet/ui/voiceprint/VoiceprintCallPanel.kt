@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.we.meet.ui.voiceprint

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

@Composable
internal fun VoiceprintCallWidget(controller: VoiceprintCallController, microphoneEnabled: Boolean,
    compact: Boolean, onSettings: (String?, String?) -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    var open by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(state.enabled) { if (!state.enabled) open = false }
    if (!state.enabled || !controller.allowed()) return
    val phase = if (!microphoneEnabled && state.phase in setOf("waiting", "starting", "sampling", "uploading")) "muted" else state.phase
    val status = stringResource(R.string.voiceprint_call_status, voiceprintCallPhaseText(phase))
    Surface(modifier, shape = MaterialTheme.shapes.small, tonalElevation = Dimens.ElevationRaised) {
        if (compact) Text(status, Modifier.padding(Dimens.SpaceXs), style = MaterialTheme.typography.labelSmall)
        else TextButton(onClick = { open = true }) { Text(status) }
    }
    if (open && !compact) VoiceprintCallPanel(state, controller, microphoneEnabled, phase,
        onDismiss = { open = false }, onSettings = onSettings)
}

@Composable
private fun VoiceprintCallPanel(state: VoiceprintCallState, controller: VoiceprintCallController,
    microphoneEnabled: Boolean, phase: String, onDismiss: () -> Unit, onSettings: (String?, String?) -> Unit) {
    val snapshot = state.snapshot
    val control = snapshot?.control
    var device by remember(controller) { mutableStateOf("") }
    LaunchedEffect(control?.deviceGroup) { device = control?.deviceGroup.orEmpty() }
    val unavailable = snapshot == null || phase == "unavailable" || state.busy || state.deviceChanged ||
        !snapshot.permission.available || control?.state in setOf("disabled", "disconnected", "source_removed")
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - Dimens.ScreenPadding * 4).coerceAtLeast(Dimens.ControlLarge)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        Text(stringResource(R.string.voiceprint_call_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.voiceprint_call_status, voiceprintCallPhaseText(phase)))
                        if (snapshot != null && control != null) {
                            val scopeName = snapshot.organizationName ?: stringResource(R.string.voiceprint_personal)
                            Text(stringResource(R.string.voiceprint_call_scope, scopeName))
                            Text(stringResource(R.string.voiceprint_call_notice, snapshot.limits.clipMs / 1000, snapshot.limits.sessionMs / 1000,
                                snapshot.limits.dailyMs / 1000, snapshot.limits.candidateRetentionSeconds / 3600))
                            Text(stringResource(R.string.voiceprint_call_confirmation))
                            Row(Modifier.fillMaxWidth().toggleable(control.sharedMicrophone, enabled = !unavailable, role = Role.Switch,
                                onValueChange = { controller.declare(true, it, control.deviceGroup) }), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                Switch(control.sharedMicrophone, onCheckedChange = null, enabled = !unavailable)
                                Text(stringResource(R.string.voiceprint_call_shared))
                            }
                            Text(stringResource(R.string.voiceprint_call_device), style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                for (value in listOf("", "headset", "handset", "computer")) {
                                    FilterChip(selected = device == value, onClick = { device = value }, enabled = !unavailable,
                                        label = { Text(if (value.isEmpty()) stringResource(R.string.voiceprint_call_choose_device) else voiceprintDeviceText(value)) })
                                }
                            }
                            Text(stringResource(R.string.voiceprint_call_declaration))
                            OutlinedButton(enabled = !unavailable && device != control.deviceGroup,
                                onClick = { controller.declare(true, control.sharedMicrophone, device) }) { Text(stringResource(R.string.voiceprint_call_save_device)) }
                            Button(enabled = !unavailable && snapshot.permission.allowAccumulation && microphoneEnabled && !control.sharedMicrophone &&
                                control.deviceGroup.isNotEmpty() && device == control.deviceGroup && (control.paused || control.state != "ready"),
                                onClick = { controller.declare(false, false, control.deviceGroup) }) { Text(stringResource(R.string.voiceprint_call_resume)) }
                            OutlinedButton(enabled = !unavailable && !control.paused,
                                onClick = { controller.declare(true, control.sharedMicrophone, control.deviceGroup) }) { Text(stringResource(R.string.voiceprint_call_pause)) }
                            control.runtime.remainingMs?.let { Text(stringResource(R.string.voiceprint_call_remaining, it.sessionMs / 1000, it.dailyMs / 1000)) }
                            OutlinedButton(enabled = !state.busy && snapshot.permission.allowAccumulation,
                                onClick = { controller.disableAccumulation() }) { Text(stringResource(R.string.voiceprint_call_disable_accumulation, scopeName)) }
                            if (control.stopReason == "mixed_speaker") Text(stringResource(R.string.voiceprint_call_mixed_speaker))
                            else if (control.runtime.reason.isNotEmpty()) Text(voiceprintCallReasonText(control.runtime.reason))
                            TextButton(enabled = !state.busy, onClick = { onSettings(snapshot.organizationId, snapshot.organizationName) }) {
                                Text(stringResource(R.string.voiceprint_call_settings))
                            }
                        }
                        state.failure?.let { Text(voiceprintCallFailureText(it), color = MaterialTheme.colorScheme.error) }
                        TextButton(enabled = !state.busy && !state.loading, onClick = { controller.refresh() }) { Text(stringResource(R.string.voiceprint_reload)) }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = Dimens.ScreenPadding)) {
                        Text(stringResource(R.string.voiceprint_call_close))
                    }
                }
            }
        }
    }
}
@Composable internal fun voiceprintDeviceText(value: String): String = stringResource(when (value) {
    "headset" -> R.string.voiceprint_device_headset; "handset" -> R.string.voiceprint_device_handset
    "computer" -> R.string.voiceprint_device_computer; else -> R.string.voiceprint_device_default
})
@Composable private fun voiceprintCallPhaseText(value: String) = stringResource(when (value) {
    "stopped" -> R.string.voiceprint_call_phase_stopped; "waiting" -> R.string.voiceprint_call_phase_waiting
    "starting" -> R.string.voiceprint_call_phase_starting; "sampling" -> R.string.voiceprint_call_phase_sampling
    "uploading" -> R.string.voiceprint_call_phase_uploading; "changing" -> R.string.voiceprint_call_phase_changing
    "device_changed" -> R.string.voiceprint_call_phase_device_changed; "muted" -> R.string.voiceprint_call_phase_muted
    else -> R.string.voiceprint_call_phase_unavailable
})
@Composable private fun voiceprintCallFailureText(value: VoiceprintCallFailure) = stringResource(when (value) {
    VoiceprintCallFailure.UNAVAILABLE -> R.string.voiceprint_call_error_unavailable
    VoiceprintCallFailure.CONFLICT -> R.string.voiceprint_call_error_conflict
    VoiceprintCallFailure.LOGIN_CHANGED -> R.string.voiceprint_call_error_authentication_changed
    VoiceprintCallFailure.CONNECTION_PENDING -> R.string.voiceprint_call_error_connection_pending
})
@Composable private fun voiceprintCallReasonText(value: String) = stringResource(when (value) {
    "ready" -> R.string.voiceprint_call_reason_ready; "disabled" -> R.string.voiceprint_call_reason_disabled
    "disconnected" -> R.string.voiceprint_call_reason_disconnected; "source_removed" -> R.string.voiceprint_call_reason_source_removed
    "authorization_required" -> R.string.voiceprint_call_reason_authorization_required; "paused" -> R.string.voiceprint_call_reason_paused
    "shared_microphone" -> R.string.voiceprint_call_reason_shared_microphone; "device_required" -> R.string.voiceprint_call_reason_device_required
    "microphone_unavailable" -> R.string.voiceprint_call_reason_microphone_unavailable; "quota_exhausted" -> R.string.voiceprint_call_reason_quota_exhausted
    else -> R.string.voiceprint_call_reason_sampling_dispatch_unavailable
})
