package com.we.meet.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.CaptureAudioRetentionDto
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.capture.CaptureRetention
import com.we.meet.data.repository.CaptureTranscriptionRepository
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CaptureRetentionPanel(viewer: String, capture: CaptureDto, repository: CaptureTranscriptionRepository) {
    var retry by remember(viewer, capture.id) { mutableIntStateOf(0) }
    val result = visibleRead(viewer, capture.id, retry, intervalMs = 5000) { repository.state(viewer, capture.id) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.capture_text_retention_title), style = MaterialTheme.typography.titleMedium)
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetInlineErrorState(onRetry = { retry++ }, message = stringResource(R.string.capture_text_retention_error))
                else -> CaptureRetentionContent(result.getOrNull()?.audioRetention) { retry++ }
            }
        }
    }
}

@Composable
internal fun CaptureRetentionContent(retention: CaptureAudioRetentionDto?, onRetry: () -> Unit) {
    if (retention?.mode != "text" || runCatching { CaptureRetention.validate(retention) }.isFailure) {
        WeMeetInlineErrorState(onRetry = onRetry, message = stringResource(R.string.capture_text_retention_error))
        return
    }
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
    val status = when (retention.cleanupStatus) {
        "pending" -> R.string.capture_text_cleanup_pending
        "failed" -> R.string.capture_text_cleanup_failed
        "complete" -> R.string.capture_text_cleanup_complete
        else -> R.string.capture_text_cleanup_not_started
    }
    Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.capture_text_access_until, format.format(Instant.parse(retention.temporaryUntil))), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.capture_text_retry_until, format.format(Instant.parse(retention.retryUntil))), style = MaterialTheme.typography.bodySmall)
    if (!CaptureRetention.canStartTranscription(retention)) Text(stringResource(R.string.capture_text_retry_closed), style = MaterialTheme.typography.bodySmall)
}
