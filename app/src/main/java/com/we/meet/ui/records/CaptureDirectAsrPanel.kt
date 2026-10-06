package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.repository.CaptureTranscriptionRepository
import com.we.meet.service.CaptureForegroundService
import com.we.meet.ui.theme.Dimens

/** Only renders service-owned state: leaving the page never stops the recording's ASR. */
@Composable
internal fun CaptureDirectAsrPanel(viewer: String, capture: CaptureDto, service: CaptureForegroundService,
    repository: CaptureTranscriptionRepository) {
    val direct by service.directAsrState.collectAsState()
    val recording by service.state.collectAsState()
    if(recording.local?.sealed == true && direct.phase == "idle") return
    val availability = visibleRead(viewer, capture.id, intervalMs=5000) { repository.state(viewer, capture.id) }
    if(availability?.getOrNull()?.directAvailable != true && direct.phase == "idle") return
    val active = direct.phase in setOf("connecting", "running", "preparing")
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(Dimens.SpaceM)) {
        Text(stringResource(R.string.capture_direct_asr_title), style=MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.capture_direct_asr_hint), style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(direct.phase != "saved" && (direct.phase != "idle" || recording.recording)) {
            Button(onClick={ if(active) service.saveDirectAsr() else service.startDirectAsr() },
                enabled=direct.phase != "saving" && (active || recording.recording && !recording.busy)) {
                Text(stringResource(if(active) R.string.capture_direct_asr_stop else R.string.capture_direct_asr_start))
            }
        }
        if(!active && direct.phase in setOf("paused", "error", "save_error", "saving")) {
            TextButton(onClick={ service.saveDirectAsr() }, enabled=direct.phase != "saving") {
                Text(stringResource(R.string.capture_direct_asr_save))
            }
        }
        if(direct.phase != "idle") Text(stringResource(when(direct.phase) {
            "connecting", "preparing" -> R.string.capture_direct_asr_connecting
            "running" -> R.string.capture_direct_asr_listening
            "saving" -> R.string.capture_direct_asr_finishing
            "paused" -> R.string.capture_direct_asr_paused
            "saved" -> if(direct.rows.isEmpty()) R.string.capture_direct_asr_empty else R.string.capture_direct_asr_finished
            "save_error" -> R.string.capture_direct_asr_save_failed
            else -> R.string.capture_direct_asr_failed
        }), color=if(direct.phase in setOf("error", "save_error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(direct.gap && direct.phase != "idle") Text(stringResource(R.string.capture_direct_asr_gap),
            style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        direct.rows.forEach { row -> CaptureTranscriptEntry("%02d:%02d".format(row.startMs / 60000, row.startMs / 1000 % 60), row.text) }
    }
}
