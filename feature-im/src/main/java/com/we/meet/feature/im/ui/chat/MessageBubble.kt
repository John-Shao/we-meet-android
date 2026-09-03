package com.we.meet.feature.im.ui.chat

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
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
import com.we.meet.feature.im.model.IM_SYSTEM_UID
import com.we.meet.feature.im.model.CardResolution
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.feature.im.model.MessageContentParser
import com.we.meet.feature.im.model.formatCallDuration
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
    /**
     * 发送者是群机器人时的一行说明(对标飞书:名字后跟「机器人」标签 + 描述)。
     * null = 真人。刻意是独立参数而不是拼进 senderName —— 名字字符串会被写进
     * 引用条和合并转发快照发到服务端,加了后缀就永久冻在历史里。
     */
    senderBotDescription: String? = null,
    /** 发送者是群机器人。与 [senderBotDescription] 分开:描述可以为空。 */
    senderIsBot: Boolean = false,
    receiptLabel: String?,
    onReceiptClick: (() -> Unit)? = null,
    onImageClick: (objectKey: String) -> Unit,
    onFileClick: (key: String, name: String) -> Unit,
    resolveMediaUrl: suspend (String) -> String?,
    /** This message was recalled — render a tombstone line instead of content. */
    recalled: Boolean = false,
    /** Aggregated reactions on this message: emoji → reacting uids. */
    reactions: Map<String, List<String>> = emptyMap(),
    /** 卡片按钮的叠加层(A2):actions 块 key → 定局结果。只有 rich-card 用得上。 */
    cardResolved: Map<String, CardResolution> = emptyMap(),
    /** 点一个 callback 按钮。null = 按钮渲染成禁用态。 */
    onCardButton: ((String) -> Unit)? = null,
    /** Long-press on the bubble → open the message action menu. */
    onLongPress: (() -> Unit)? = null,
    /** @-mention highlighting (P10): all candidate names + the subset meaning "you". */
    mentionNames: List<String> = emptyList(),
    selfMentionNames: List<String> = emptyList(),
    /** Tap the sender's avatar → navigate to their personal info page. */
    onAvatarClick: (() -> Unit)? = null,
    /** P4.1 群语音卡片: tap Join on an ongoing group-call card. */
    onJoinGroupCall: ((slug: String) -> Unit)? = null,
    /** P4.1: this card's call already ended (matching end-record downstream). */
    groupCallEnded: Boolean = false,
    /** P8 日程卡片: tap 查看详情 → 打开日程详情页(app 层接 EVENT_DETAIL 路由)。 */
    onOpenEvent: ((eventId: String) -> Unit)? = null,
    /** 分享云文档卡片: tap「查看文档」→ 打开该文档(app 层接文档查看器)。 */
    onOpenDoc: ((url: String) -> Unit)? = null,
    /** 分享会议卡片: tap「加入会议」→ 按 slug 走入会预览(app 层接 joinPreview)。 */
    onJoinMeeting: ((slug: String) -> Unit)? = null,
) {
    val content = remember(message.mid) {
        MessageContentParser.parse(message.contentType, message.body)
    }

    if (recalled) {
        // Web parity: tombstone replaces the bubble entirely.
        Box(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXs), contentAlignment = Alignment.Center) {
            Text(
                text = if (isOwn) stringResource(R.string.im_recalled_self)
                else stringResource(R.string.im_recalled_other, senderName.orEmpty()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (content is MessageContent.System) {
        // Centered gray notice, no bubble.
        Box(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXs), contentAlignment = Alignment.Center) {
            Text(
                text = content.body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // P8-UX:后端 SYSTEM 兜底注入的日程变更/取消卡 → 居中,无发送者归属
    // (正常路径卡片已由组织者 IM 身份发出,走下方常规气泡;此分支只兜
    // uid 解析失败的降级,否则全零 uid 会渲染成「?」气泡)。
    if (content is MessageContent.EventCard && message.senderUid == IM_SYSTEM_UID) {
        Box(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXs), contentAlignment = Alignment.Center) {
            EventCardBubble(content, isOwn = false, onLongPress = onLongPress) {
                onOpenEvent?.invoke(content.eventId)
            }
        }
        return
    }

    if (content is MessageContent.PhoneViewed) {
        // Centered notice, perspective-aware: sender = the viewer (P3).
        Box(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXs), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(
                    if (isOwn) R.string.im_phone_viewed_by_me
                    else R.string.im_phone_viewed_by_peer
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
    ) {
        // 对方消息:左侧头像(私聊 + 群聊都显示,对齐企业微信/微信)。
        if (!isOwn) {
            MemberAvatar(
                name = senderName.orEmpty(),
                url = senderAvatarUrl,
                cacheKey = "im-avatar:${message.senderUid}",
                size = Dimens.AvatarS,
                modifier = if (onAvatarClick != null) Modifier.clickable(onClick = onAvatarClick) else Modifier,
            )
            Spacer(Modifier.width(Dimens.SpaceS))
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
                    text = if (senderIsBot) {
                        val chip = stringResource(R.string.im_bots_chip)
                        if (senderBotDescription.isNullOrBlank()) {
                            "$senderName · $chip"
                        } else {
                            "$senderName · $chip | $senderBotDescription"
                        }
                    } else {
                        senderName
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = Dimens.SpaceXxs),
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
                is MessageContent.CallLog -> CallLogBubble(content, isOwn)
                is MessageContent.GroupCall -> GroupCallBubble(
                    content, groupCallEnded, onJoinGroupCall,
                )
                is MessageContent.EventCard -> EventCardBubble(
                    content, isOwn, onLongPress,
                ) { onOpenEvent?.invoke(content.eventId) }
                is MessageContent.DocCard -> DocCardBubble(
                    content, onLongPress,
                ) { onOpenDoc?.invoke(content.url) }
                is MessageContent.MeetingCard -> MeetingCardBubble(
                    content, onLongPress,
                ) { onJoinMeeting?.invoke(content.slug) }
                is MessageContent.CalendarCard -> CalendarCardBubble(content, onLongPress)
                is MessageContent.RichCard -> RichCardBubble(
                    body = content.body,
                    resolved = cardResolved,
                    onClickButton = onCardButton,
                    onOpenDoc = onOpenDoc,
                )
                is MessageContent.RichText -> RichTextBubble(
                    body = content.body,
                    isOwn = isOwn,
                    selfMentionNames = selfMentionNames,
                    onLongPress = onLongPress,
                )
                is MessageContent.Unsupported -> UnsupportedBubble(isOwn)
                // card-state 是控制消息:它被 isControlType 过滤在渲染之外,
                // 由 ChatViewModel 回放成叠加层。走到这里说明过滤漏了。
                is MessageContent.CardState -> Unit
                // Control/system rows never reach here (filtered / early-returned).
                is MessageContent.Recall, is MessageContent.Reaction,
                is MessageContent.System, is MessageContent.PhoneViewed -> Unit
            }
            if (reactions.isNotEmpty()) {
                ReactionChips(reactions)
            }
            if (receiptLabel != null) {
                Text(
                    text = receiptLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Dimens.SpaceXxs)
                        .then(
                            if (onReceiptClick != null) Modifier.clickable(onClick = onReceiptClick)
                            else Modifier
                        ),
                )
            }
        }
        // 自己消息:右侧头像(对齐企业微信/微信)。
        if (isOwn) {
            Spacer(Modifier.width(Dimens.SpaceS))
            MemberAvatar(
                name = senderName.orEmpty(),
                url = senderAvatarUrl,
                cacheKey = "im-avatar:${message.senderUid}",
                size = Dimens.AvatarS,
            )
        }
    }
}

private val bubbleShape = RoundedCornerShape(Dimens.CornerM)

@Composable
private fun TextBubble(
    body: String,
    isOwn: Boolean,
    mentionNames: List<String> = emptyList(),
    selfMentionNames: List<String> = emptyList(),
) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
    ) {
        Text(
            text = mentionAnnotated(body, mentionNames, selfMentionNames),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = Dimens.Chat.BubbleMaxWidth)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
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
    val mention = WeMeetTheme.extras.im
    return buildAnnotatedString {
        var i = 0
        while (i < body.length) {
            if (body[i] == '@') {
                val rest = body.substring(i + 1)
                val hit = sorted.firstOrNull { rest.startsWith(it) }
                if (hit != null) {
                    val style = if (hit in selfNames) {
                        SpanStyle(
                            background = mention.mentionSelfBg,
                            color = mention.mentionSelfFg,
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
        modifier = (if (objectKey.startsWith("emoji/")) Modifier.size(Dimens.AvatarXxl) else Modifier
            .widthIn(max = Dimens.Chat.CardMinWidth)
            .heightIn(min = Dimens.Chat.ImageMinHeight, max = Dimens.Chat.ImageMaxHeight))
            .clip(bubbleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(
                text = stringResource(R.string.im_image_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Dimens.SpaceXl),
            )
            url == null -> CircularProgressIndicator(modifier = Modifier.padding(Dimens.SpaceXl).size(Dimens.IconSmall))
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
                modifier = if (objectKey.startsWith("emoji/")) Modifier.size(Dimens.AvatarXxl)
                else Modifier.widthIn(max = Dimens.Chat.CardMinWidth).heightIn(max = Dimens.Chat.BubbleMaxWidth),
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
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = Dimens.Chat.BubbleMaxWidth)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconXl),
            )
            Column(modifier = Modifier.padding(start = Dimens.SpaceS)) {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress),
    ) {
        // Web parity: longer clip → wider bubble, clamped.
        val bubbleWidth = (96 + seconds.coerceAtMost(60L).toInt() * 2).coerceAtMost(220)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(bubbleWidth.dp)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Icon(
                if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Spacer(Modifier.width(Dimens.SpaceXs))
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
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.Chat.BubbleMaxWidth)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(Dimens.CornerS),
            ) {
                Text(
                    text = listOf(content.quotedSender, content.quotedSnippet)
                        .filter { it.isNotBlank() }
                        .joinToString(": "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXs))
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
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
        modifier = Modifier.combinedClickable(
            onClick = { showDialog = true },
            onLongClick = onLongPress,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = Dimens.Chat.BubbleMinWidth, max = Dimens.Chat.BubbleMaxWidth)
                .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Dimens.SpaceXxs),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = Dimens.SpaceXs))
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
            LazyColumn(modifier = Modifier.heightIn(max = Dimens.Chat.MergedListMaxHeight)) {
                items(content.items.size) { i ->
                    val item = content.items[i]
                    Column(Modifier.padding(vertical = Dimens.SpaceXs)) {
                        Text(
                            text = item.sender,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        modifier = Modifier.padding(top = Dimens.SpaceXxs),
    ) {
        reactions.forEach { (emoji, uids) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Dimens.CornerS),
            ) {
                Text(
                    text = if (uids.size > 1) "$emoji ${uids.size}" else emoji,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs),
                )
            }
        }
    }
}

@Composable
private fun CallLogBubble(content: MessageContent.CallLog, isOwn: Boolean) {
    // "语音通话 · 对方无应答" style, with a phone/camera glyph — mirrors Feishu's
    // missed-call rows. Tapping to redial is a P2 nicety.
    //
    // Perspective-aware wording: the call-log's SENDER is always the caller,
    // so isOwn means "I placed this call". The same declined record must read
    // 「对方已拒绝」 to the caller but 「已拒绝」 to the callee who tapped it.
    // Completed calls render the duration instead — identical on both sides.
    // P4-M3: 未接通的会议邀请记录前缀「会议邀请」——样式上区别于未接来电。
    val media = if (content.meetInvite) {
        stringResource(R.string.im_calllog_meet_invite)
    } else {
        stringResource(
            if (content.media == "video") R.string.im_calllog_video else R.string.im_calllog_voice
        )
    }
    val result = if (content.result == "completed") {
        formatCallDuration(content.durationSec)
    } else {
        stringResource(
            when (content.result) {
                "canceled" ->
                    if (isOwn) R.string.im_calllog_canceled else R.string.im_calllog_canceled_peer
                "declined" ->
                    if (isOwn) R.string.im_calllog_declined else R.string.im_calllog_declined_peer
                "busy" ->
                    if (isOwn) R.string.im_calllog_busy else R.string.im_calllog_busy_peer
                "unreachable" ->
                    if (isOwn) R.string.im_calllog_unreachable else R.string.im_calllog_unreachable_peer
                else ->
                    if (isOwn) R.string.im_calllog_missed else R.string.im_calllog_missed_peer
            }
        )
    }
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Icon(
                if (content.media == "video") Icons.Filled.Videocam else Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(
                text = "$media · $result",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Dimens.SpaceXs),
            )
        }
    }
}

/** P4.1 群语音「进行中」卡片:通话中可点加入,结束后灰态。 */
@Composable
private fun GroupCallBubble(
    content: MessageContent.GroupCall,
    ended: Boolean,
    onJoin: ((slug: String) -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
        modifier = if (!ended && onJoin != null) {
            Modifier.clickable { onJoin(content.slug) }
        } else {
            Modifier
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(
                text = stringResource(R.string.im_calllog_voice) + " · " +
                    stringResource(
                        if (ended) R.string.im_group_card_ended
                        else R.string.im_group_card_ongoing
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Dimens.SpaceXs),
            )
            if (!ended) {
                Text(
                    text = stringResource(R.string.im_group_card_join),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Dimens.SpaceS),
                )
            }
        }
    }
}

@Composable
private fun UnsupportedBubble(isOwn: Boolean) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = bubbleShape,
    ) {
        Text(
            text = stringResource(R.string.im_preview_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        )
    }
}
