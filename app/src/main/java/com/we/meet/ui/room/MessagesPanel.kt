package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.AvatarOnFallback
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.data.chat.ChatMessageUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会中消息. Renders inside a ModalBottomSheet that occupies 61.8% of
 * the viewport (golden ratio, matches RoomAiSheet). Sheet collapse
 * gestures (drag down on the handle or scrim tap) call [onDismiss];
 * the back button does the same via the sheet's built-in handling.
 *
 * The transcript area uses `weight(1f)` so it expands to fill whatever
 * vertical space is left after the title row + input row, regardless
 * of IME state (input row sticks to the bottom via `imePadding`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesPanel(
    messages: List<ChatMessageUi>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.618f)
                .imePadding(),
        ) {
            Header()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MessageList(
                messages = messages,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InputBar(onSend = onSend)
        }
    }
}

@Composable
private fun Header() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.SheetHeaderHeight),
    ) {
        Text(
            text = stringResource(R.string.room_messages_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessageUi>,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.room_message_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Dimens.ScreenPadding,
            vertical = Dimens.SpaceM,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        items(items = messages, key = { it.id }) { MessageRow(it) }
    }
}

@Composable
private fun MessageRow(message: ChatMessageUi) {
    val configuration = LocalConfiguration.current
    val maxBubbleWidth = configuration.screenWidthDp.dp * 0.72f
    val locale = configuration.locales[0]
    val timeFormatter = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Avatar(name = message.senderName, identity = message.senderIdentity)
        Spacer(Modifier.width(Dimens.SpaceS))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.isHost) {
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    HostTag()
                }
                Spacer(Modifier.width(Dimens.SpaceS))
                Text(
                    text = timeFormatter.format(Date(message.timestampMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXs))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(Dimens.CornerS),
                modifier = Modifier.widthIn(max = maxBubbleWidth),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String, identity: String) {
    val initials = avatarInitials(name.ifBlank { identity })
    Box(
        modifier = Modifier
            .size(Dimens.AvatarS)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(colors = WeMeetTheme.extras.room.avatarGradient),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = AvatarOnFallback,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun HostTag() {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        shape = RoundedCornerShape(Dimens.CornerXs),
    ) {
        Text(
            text = stringResource(R.string.room_message_host_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs),
        )
    }
}

@Composable
private fun displayName(message: ChatMessageUi): String {
    val suffix = if (message.isLocal) stringResource(R.string.room_participant_me) else ""
    return message.senderName + suffix
}

@Composable
private fun InputBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        val toSend = text.trim()
        if (toSend.isNotEmpty()) {
            onSend(toSend)
            text = ""
        }
    }
    val isEnabled = text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    text = stringResource(R.string.room_message_input_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(
            onClick = submit,
            enabled = isEnabled,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.room_message_send),
                tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

private fun avatarInitials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    // Prefer the last 1–2 CJK characters, matching the reference UI ("邵建永" → "建永").
    // For Latin names we fall back to the leading character uppercased.
    val firstChar = trimmed[0]
    return if (firstChar.code in 0x4E00..0x9FFF) {
        trimmed.takeLast(2)
    } else {
        trimmed.first().uppercase(Locale.getDefault())
    }
}
