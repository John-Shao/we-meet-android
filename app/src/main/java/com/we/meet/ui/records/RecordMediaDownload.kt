package com.we.meet.ui.records

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.repository.MeetingRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ask for a fresh attachment URL, then let the browser stream large files. */
@Composable
internal fun RecordMediaDownload(repository: MeetingRecordRepository, viewer: String, record: RecordDto) {
    if (record.sourceType != "upload" || !record.capabilities.downloadMedia) return
    key(viewer, record.id, record.revision) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var handedOff by remember { mutableStateOf(false) }
        Column {
            TextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true; failed = false; handedOff = false
                    scope.launch {
                        try {
                            val media = repository.media(viewer, record.id, record.revision, download = true).getOrThrow()
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            handedOff = true
                        } catch (canceled: CancellationException) { throw canceled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }
            }) { Text(stringResource(if (busy) R.string.record_media_download_preparing else R.string.record_media_download)) }
            if (failed) Text(stringResource(R.string.record_media_download_failed))
            if (handedOff) Text(stringResource(R.string.record_media_download_handoff))
        }
    }
}
