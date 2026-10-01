package com.we.meet.feature.assistant.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.we.meet.feature.assistant.R
import java.io.File
import java.util.UUID

class AssistantHistoryFileProvider : FileProvider(R.xml.assistant_history_paths)

/** Small selections share inline; full conversations use a UTF-8 attachment without truncation. */
fun historyShareIntent(context: Context, text: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    if (text.length <= 100000) putExtra(Intent.EXTRA_TEXT, text)
    else {
        val folder = File(context.cacheDir, "assistant-share").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.delete() }
        val file = File(folder, "conversation-${UUID.randomUUID()}.txt")
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.assistant.history.files", file)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
