package com.we.meet.ui.records

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.repository.UploadTranslationRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the detail screen, outside the private body removed on background. */
@Composable
internal fun rememberTranslationExporter(
    repository: UploadTranslationRepository,
    viewer: String,
    recordId: String,
): (String, String) -> Unit = key(viewer, recordId) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translation by rememberSaveable { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selectedFormat = format
        val selectedTranslation = translation
        translation = null
        format = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || selectedFormat == null || selectedTranslation == null || destination == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    // Reauthorize on return; never retain transcript bytes in the picker.
                    repository.export(viewer, recordId, selectedTranslation, selectedFormat).getOrThrow().use { body ->
                        checkNotNull(context.contentResolver.openOutputStream(destination, "w")).use { output ->
                            body.byteStream().use { it.copyTo(output) }
                        }
                    }
                }
            }
            if (written.isFailure) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { context.contentResolver.delete(destination, null, null) }
            }
            busy = false
            val error = written.exceptionOrNull()
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(context, context.getString(
                    if (written.isFailure) R.string.records_export_failed else R.string.records_export_saved,
                ), Toast.LENGTH_SHORT).show()
            }
        }
    }
    if (busy) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.records_export_running)) },
        text = { WeMeetInlineLoading() },
        confirmButton = {},
    )
    val launch: (String, String) -> Unit = { translationId, selectedFormat ->
        val mime = when (selectedFormat) {
            "txt" -> "text/plain"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> error("Unsupported transcript format")
        }
        if (!busy && format == null) {
            format = selectedFormat
            translation = translationId
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, "translation-${translationId.take(8)}.$selectedFormat")
            })
        }
    }
    launch
}
