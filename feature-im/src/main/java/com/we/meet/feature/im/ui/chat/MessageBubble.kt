package com.we.meet.feature.im.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jusi.lightim.Message
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser
import com.we.meet.feature.im.model.formatFileSize

/**
 * One message row. Rendering dispatches on the parsed [MessageContent] — adding
 * a Phase-2 content type is one extra branch here (plus its parser subtype).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    isGroup: Boolean,
    senderName: String?,
    senderAvatarUrl: String?,
    receiptLabel: String?,
    onReceiptClick: (() -> Unit)? = null,
    onImageClick: (objectKey: String) -> Unit,
    onFileClick: (key: String, name: String) -> Unit,
    resolveMediaUrl: suspend (String) -> String?,
    /** This message was recalled — render a tombstone line instead of content. */
    recalled: Boolean = false,
    /** Aggregated reactions on this message: emoji → reacting uids. */
    reactions: Map<String, List<String>> = emptyMap(),
    /** Long-press on the bubble → open the message action menu. */
    onLongPress: (() -> Unit)? = null,
    /** @-mention highlighting (P10): all candidate names + the subset meaning "you". */
    mentionNames: List<String> = emptyList(),
    selfMentionNames: List<String> = emptyList(),
    /** Tap the sender's avatar → navigate to their personal info page. */
    onAvatarClick: (() -> Unit)? = null,
) {
    val content = remember(message.mid) {
        MessageContentParser.parse(message.contentType, message.body)
    }

    if (recalled) {
        // Web parity: tombstone replaces the bubble entirely.
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                text = if (isOwn) stringResource(R.string.im_recalled_self)
                else stringResource(R.string.im_recalled_other, senderName.orEmpty()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }

    if (content is MessageContent.System) {
        // Centered gray notice, no bubble.
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                text = content.body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        // 对方消息:左侧头像(私聊 + 群聊都显示,对齐企业微信/微信)。
        if (!isOwn) {
            MemberAvatar(
                name = senderName.orEmpty(),
                url = senderAvatarUrl,
                cacheKey = "im-avatar:${message.senderUid}",
                size = 36.dp,
                modifier = if (onAvatarClick != null) Modifier.clickable(onClick = onAvatarClick) else Modifier,
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = if (onLongPress != null) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
            } else Modifier,
        ) {
            if (!isOwn && isGroup && !senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            when (content) {
                is MessageContent.Text ->
                    TextBubble(content.body, isOwn, mentionNames, selfMentionNames)
                is MessageContent.Image -> ImageBubble(
                    objectKey = content.objectKey,
                    onClick = { onImageClick(content.objectKey) },
                    onLongPress = onLongPress,
                    resolveMediaUrl = resolveMediaUrl,
                )
                is MessageContent.File -> FileBubble(
                    name = content.name,
                    size = content.size,
                    isOwn = isOwn,
                    onClick = { onFileClick(content.key, content.name) },
                    onLongPress = onLongPress,
                )
                is MessageContent.Voice -> VoiceBubble(
                    key = content.key,
                    durationMs = content.durationMs,
                    isOwn = isOwn,
                    resolveMediaUrl = resolveMediaUrl,
                    onLongPress = onLongPress,
                )
                is MessageContent.Quote ->
                    QuoteBubble(content, isOwn, mentionNames, selfMentionNames)
                is MessageContent.Merged -> MergedBubble(content, isOwn, onLongPress)
                is MessageContent.Unsupported -> UnsupportedBubble(isOwn)
                // Control/system rows never reach here (filtered / early-returned).
                is MessageContent.Recall, is MessageContent.Reaction,
                is MessageContent.System -> Unit
            }
            if (reactions.isNotEmpty()) {
                ReactionChips(reactions)
            }
            if (receiptLabel != null) {
                Text(
                    text = receiptLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (onReceiptClick != null) Modifier.clickable(onClick = onReceiptClick)
                            else Modifier
                        ),
                )
            }
        }
        // 自己消息:右侧头像(对齐企业微信/微信)。
        if (isOwn) {
            Spacer(Modifier.width(8.dp))
            MemberAvatar(
                name = senderName.orEmpty(),
                url = senderAvatarUrl,
                cacheKey = "im-avatar:${message.senderUid}",
                size = 36.dp,
            )
        }
    }
}

private val bubbleShape = RoundedCornerShape(12.dp)

@Composable
private fun TextBubble(
    body: String,
    isOwn: Boolean,
    mentionNames: List<String> = emptyList(),
    selfMentionNames: List<String> = emptyList(),
) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
    ) {
        Text(
            text = mentionAnnotated(body, mentionNames, selfMentionNames),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Highlight `@name` tokens (P10, mirrors web renderBody): self-mention / 所有人 →
 * amber pill; other members → bold + primary tint. Longest name first to avoid
 * partial matches. No `@` or no candidates → plain text.
 */
@Composable
private fun mentionAnnotated(
    body: String,
    names: List<String>,
    selfNames: List<String>,
): AnnotatedString {
    if (names.isEmpty() || !body.contains('@')) return AnnotatedString(body)
    val sorted = names.filter { it.isNotBlank() }.sortedByDescending { it.length }
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var i = 0
        while (i < body.length) {
            if (body[i] == '@') {
                val rest = body.substring(i + 1)
                val hit = sorted.firstOrNull { rest.startsWith(it) }
                if (hit != null) {
                    val style = if (hit in selfNames) {
                        SpanStyle(
                            background = Color(0xFFFDE68A),
                            color = Color(0xFF92400E),
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        SpanStyle(color = primary, fontWeight = FontWeight.Bold)
                    }
                    withStyle(style) { append("@$hit") }
                    i += 1 + hit.length
                    continue
                }
            }
            append(body[i])
            i++
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageBubble(
    objectKey: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    resolveMediaUrl: suspend (String) -> String?,
) {
    var url by remember(objectKey) { mutableStateOf<String?>(null) }
    var failed by remember(objectKey) { mutableStateOf(false) }
    LaunchedEffect(objectKey) {
        url = resolveMediaUrl(objectKey)
        if (url == null) failed = true
    }

    Box(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .heightIn(min = 80.dp, max = 280.dp)
            .clip(bubbleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(
                text = stringResource(R.string.im_image_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            url == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(20.dp))
            else -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    // Presigned URLs rotate; the immutable object key is the identity.
                    .memoryCacheKey(objectKey)
                    .diskCacheKey(objectKey)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.widthIn(max = 220.dp).heightIn(max = 280.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileBubble(
    name: String,
    size: Long,
    isOwn: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sizeText = formatFileSize(size)
                if (sizeText.isNotBlank()) {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * Voice clip: play/pause toggle + seconds label; bubble width scales with
 * duration (web parity). Playback uses a throwaway MediaPlayer on the
 * presigned URL — released on dispose or when a new clip starts.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubble(
    key: String,
    durationMs: Long,
    isOwn: Boolean,
    resolveMediaUrl: suspend (String) -> String?,
    onLongPress: (() -> Unit)?,
) {
    val seconds = ((durationMs + 999) / 1000).coerceAtLeast(1L)
    var playing by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val player = remember(key) { VoicePlayerHolder() }
    DisposableEffect(key) { onDispose { player.release() } }

    val onToggle = {
        if (playing) {
            player.stop()
            playing = false
        } else {
            scope.launch {
                val url = resolveMediaUrl(key) ?: return@launch
                playing = true
                player.play(url) { playing = false }
            }
            Unit
        }
    }

    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress),
    ) {
        // Web parity: longer clip → wider bubble, clamped.
        val bubbleWidth = (96 + seconds.coerceAtMost(60L).toInt() * 2).coerceAtMost(220)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(bubbleWidth.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${seconds}″",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Single-owner MediaPlayer wrapper so bubbles can't leak players. */
private class VoicePlayerHolder {
    private var player: MediaPlayer? = null

    fun play(url: String, onDone: () -> Unit) {
        release()
        player = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener { start() }
            setOnCompletionListener { onDone() }
            setOnErrorListener { _, _, _ -> onDone(); true }
            prepareAsync()
        }
    }

    fun stop() = release()

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}

/** Reply bubble: dimmed quoted `sender: snippet` block above the reply text. */
@Composable
private fun QuoteBubble(
    content: MessageContent.Quote,
    isOwn: Boolean,
    mentionNames: List<String> = emptyList(),
    selfMentionNames: List<String> = emptyList(),
) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = listOf(content.quotedSender, content.quotedSnippet)
                        .filter { it.isNotBlank() }
                        .joinToString(": "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = mentionAnnotated(content.text, mentionNames, selfMentionNames),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Merged chat-record card: title + first lines; tap opens the full record dialog. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MergedBubble(content: MessageContent.Merged, isOwn: Boolean, onLongPress: (() -> Unit)?) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(
            onClick = { showDialog = true },
            onLongClick = onLongPress,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = content.title.ifBlank { stringResource(R.string.im_merged_title) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            content.items.take(3).forEach { item ->
                Text(
                    text = "${item.sender}: ${item.text}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                text = stringResource(R.string.im_merged_view_count, content.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (showDialog) {
        MergedRecordDialog(content, onDismiss = { showDialog = false })
    }
}

@Composable
private fun MergedRecordDialog(content: MessageContent.Merged, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = {
            Text(content.title.ifBlank { stringResource(R.string.im_merged_title) })
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(content.items.size) { i ->
                    val item = content.items[i]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = item.sender,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
    )
}

/** Aggregated reaction chips under a bubble: `emoji ×n`. */
@Composable
private fun ReactionChips(reactions: Map<String, List<String>>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 3.dp),
    ) {
        reactions.forEach { (emoji, uids) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = if (uids.size > 1) "$emoji ${uids.size}" else emoji,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun UnsupportedBubble(isOwn: Boolean) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
    ) {
        Text(
            text = stringResource(R.string.im_preview_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
