package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.im.R
import com.we.meet.feature.im.model.MessageContent
import com.we.meet.ui.theme.Dimens

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalendarCardBubble(
    content: MessageContent.CalendarCard,
    onLongPress: (() -> Unit)?,
) {
    val uri = LocalUriHandler.current
    Surface(
        shape = RoundedCornerShape(Dimens.CornerM),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Dimens.ElevationSubtle,
        border = BorderStroke(Dimens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(min = Dimens.Chat.CardMinWidth, max = Dimens.Chat.CardMaxWidth)
            .combinedClickable(
                enabled = content.subscribeUrl.isNotBlank(),
                onClick = { uri.openUri(content.subscribeUrl) },
                onLongClick = onLongPress,
            ),
    ) {
        Row {
            Box(
                Modifier.width(Dimens.Chat.CardAccentBarWidth).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.padding(Dimens.SpaceM)) {
                Row {
                    Icon(Icons.Filled.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        content.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Dimens.SpaceXs),
                    )
                }
                if (content.ownerName.isNotBlank()) {
                    Text(stringResource(R.string.im_calendar_owner, content.ownerName))
                }
                if (content.description.isNotBlank()) {
                    Text(content.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    pluralStringResource(
                        R.plurals.im_calendar_subscribers,
                        content.subscriberCount,
                        content.subscriberCount,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
