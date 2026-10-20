package com.we.meet.feature.assistant.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.we.meet.feature.assistant.R
import com.we.meet.ui.theme.Dimens
import java.util.Date
import java.util.Locale

/** Call-log card: topic first, with call metadata and evidence, not a transcript snippet. */
@Composable
internal fun CallHistoryCard(entry: AssistantHistoryEntry, now: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val captures = entry.rows.count { it.photo != null }
    val todos = entry.summary?.tasks?.size ?: 0
    val duration = durationClock(entry, now)
    val status = when {
        entry.endedAt == null -> stringResource(R.string.assistant_history_call_ongoing)
        entry.endedAt - entry.startedAt <= 1_000 -> stringResource(R.string.assistant_history_call_interrupted)
        else -> stringResource(R.string.assistant_history_call_completed)
    }
    val time = DateFormat.getTimeFormat(context).format(Date(entry.startedAt))
    val meta = listOfNotNull(
        context.getString(R.string.assistant_history_call),
        time,
        duration.takeIf { it.isNotBlank() },
        status,
    ).joinToString(" · ")

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTagForCall(entry.id)) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(Dimens.CornerM),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(Dimens.SpaceS),
                )
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Column(Modifier.weight(1f)) {
                Text(
                    text = callTopic(entry, context),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.summary != null || captures > 0 || todos > 0) {
                    Spacer(Modifier.size(Dimens.SpaceS))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        if (entry.summary != null) Badge(stringResource(R.string.assistant_history_call_summary))
                        if (todos > 0) Badge(stringResource(R.string.assistant_history_call_todos, todos))
                        if (captures > 0) Badge(stringResource(R.string.assistant_history_call_captures, captures))
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerXs))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            .padding(horizontal = Dimens.SpaceS, vertical = 2.dp),
    )
}

private fun durationClock(entry: AssistantHistoryEntry, now: Long): String {
    if (entry.endedAt == null) return ""
    val total = ((entry.endedAt - entry.startedAt).coerceAtLeast(0) / 1000).toInt()
    if (total < 60) return "${total}s"
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes < 60) "%d:%02d".format(Locale.US, minutes, seconds)
    else "%d:%02d:%02d".format(Locale.US, minutes / 60, minutes % 60, seconds)
}

private fun callTopic(entry: AssistantHistoryEntry, context: android.content.Context): String {
    entry.summary?.summary
        ?.lineSequence()
        ?.map { it.trim().trimStart('#', '-', '*', ' ') }
        ?.firstOrNull { it.isNotBlank() }
        ?.take(100)
        ?.let { return it }
    val assistant = entry.rows.asReversed().firstOrNull {
        it.role == "assistant" && it.text.isMeaningful()
    }
    val user = entry.rows.firstOrNull {
        it.role == "user" && it.text.isMeaningful()
    }
    (assistant ?: user)?.text?.trim()?.take(100)?.let { return it }
    if (entry.rows.any { it.photo != null }) {
        return context.getString(R.string.assistant_screen_read_caption)
    }
    return context.getString(R.string.assistant_history_no_summary)
}

private fun String.isMeaningful(): Boolean {
    val value = trim()
    if (value.length < 4) return false
    return value.lowercase(Locale.ROOT) !in setOf(
        "嗯", "嗯。", "好的", "好的。", "谢谢", "谢谢。", "ok", "okay", "hello", "hi",
    )
}

private fun Modifier.testTagForCall(id: String): Modifier = testTag("call-history-$id")
