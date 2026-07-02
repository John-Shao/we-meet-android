package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
) {
    val content = remember(message.mid) {
        MessageContentParser.parse(message.contentType, message.body)
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
        if (!isOwn && isGroup) {
            MemberAvatar(
                name = senderName.orEmpty(),
                url = senderAvatarUrl,
                cacheKey = "im-avatar:${message.senderUid}",
                size = 32.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start) {
            if (!isOwn && isGroup && !senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            when (content) {
                is MessageContent.Text -> TextBubble(content.body, isOwn)
                is MessageContent.Image -> ImageBubble(
                    objectKey = content.objectKey,
                    onClick = { onImageClick(content.objectKey) },
                    resolveMediaUrl = resolveMediaUrl,
                )
                is MessageContent.File -> FileBubble(
                    name = content.name,
                    size = content.size,
                    isOwn = isOwn,
                    onClick = { onFileClick(content.key, content.name) },
                )
                is MessageContent.Unsupported -> UnsupportedBubble(isOwn)
                is MessageContent.System -> Unit // handled above
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
    }
}

private val bubbleShape = RoundedCornerShape(12.dp)

@Composable
private fun TextBubble(body: String, isOwn: Boolean) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ImageBubble(
    objectKey: String,
    onClick: () -> Unit,
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
            .clickable(onClick = onClick),
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

@Composable
private fun FileBubble(name: String, size: Long, isOwn: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = bubbleShape,
        onClick = onClick,
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
