package com.we.meet.feature.im.ui.chat

import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject

/** The message contains only identity; private content and viewer role come from the API. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MeetingRecordCardBubble(content: MessageContent.MeetingRecordCard, onLongPress: (() -> Unit)?, onOpen: ((String) -> Unit)?) {
    val deps = LocalContext.current.applicationContext as? ImDeps
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var preview by remember(content.recordId, content.scope) { mutableStateOf<JSONObject?>(null) }
    var thumbnail by remember(content.recordId, content.scope) { mutableStateOf<ImageBitmap?>(null) }
    var unavailable by remember(content.recordId, content.scope) { mutableStateOf(false) }
    val base = deps?.baseUrl?.trimEnd('/')
    LaunchedEffect(content.recordId, content.scope, lifecycle, deps) {
        if (deps == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            deps.authedOkHttp.newCall(Request.Builder().url("$base/api/v1.0/meeting-records/${content.recordId}/collaboration/${content.scope}/preview/").header("Cache-Control", "no-store").build()).execute().use {
                                check(it.isSuccessful)
                                JSONObject(requireNotNull(it.body).string()).also { value -> check(value.getString("record_id") == content.recordId && value.getString("scope") == content.scope) }
                            }
                        }.getOrNull()
                    }
                    preview = result; unavailable = result == null
                    if (result == null) { thumbnail = null; break }
                    if (thumbnail == null && result.optString("media_type") == "video") {
                        val url = result.optString("media_url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        if (url != null) thumbnail = withContext(Dispatchers.IO) {
                            runCatching {
                                val retriever = MediaMetadataRetriever()
                                try { retriever.setDataSource(url, emptyMap()); retriever.getFrameAtTime(0)?.asImageBitmap() }
                                finally { retriever.release() }
                            }.getOrNull()
                        }
                    }
                    delay(15000)
                }
            } finally { preview = null; thumbnail = null }
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(min = Dimens.Chat.CardMinWidth, max = Dimens.Chat.CardMaxWidth).combinedClickable(
            onClick = { if (base != null) onOpen?.invoke("$base/meeting/records/${content.recordId}?tab=${if (content.scope == "minutes") "summary" else "overview"}") }, onLongClick = onLongPress,
        )) {
        Column(Modifier.padding(Dimens.SpaceM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(if (content.scope == "minutes") R.string.im_material_minutes else R.string.im_material_record), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(content.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (content.scope == "minutes") {
                Icon(Icons.Outlined.Description, contentDescription = null)
                preview?.optString("excerpt")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            } else {
                thumbnail?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(Dimens.IconIllustration))
            }
            HorizontalDivider()
            Text(stringResource(when {
                unavailable -> R.string.im_material_unavailable
                preview == null -> R.string.im_material_loading
                preview?.optString("role") == "manager" -> R.string.im_material_manager
                preview?.optString("role") == "editor" -> R.string.im_material_editor
                else -> R.string.im_material_reader
            }), style = MaterialTheme.typography.bodySmall)
        }
    }
}
