package com.we.meet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.ui.theme.Dimens
import com.we.meet.R
import com.we.meet.data.history.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * P8(对标飞书):行点击进详情(已结束)/重进会议(进行中),删除等操作
 * 收进历史详情页;长按删除已移除。
 */
@Composable
fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (entry: HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.history_section_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = Dimens.SpaceM),
        )
        entries.forEach { entry ->
            HistoryRow(entry = entry, onClick = { onEntryClick(entry) })
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.SpaceM),
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.ListThumbnail)
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(Dimens.SpaceM))
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = entry.name.ifBlank { entry.slug },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // 历史列表带年份(与 Web 端对齐);服务端合成行(本机从未
                    // 加入)firstJoinedAtMs=0 退回房间创建时间。
                    text = HistoryTimeFormatter.fullDateTimeLocalized(
                        LocalContext.current,
                        entry.firstJoinedAtMs.takeIf { it > 0 } ?: entry.createdAtMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = Dimens.DividerIndentThumbnail),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = Dimens.DividerThin,
    )
}

object HistoryTimeFormatter {
    private fun timeFmt() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun monthDayFmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())
    private fun fullDateFmt() = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())

    /** "<today> HH:mm" if same calendar day, else the localized month-day-time.
     *  Both the today prefix and the month-day pattern are localized, so
     *  callers pass a [Context]. */
    fun relativeListTimestamp(context: android.content.Context, epochMs: Long): String =
        if (isToday(epochMs)) {
            "${context.getString(R.string.history_today_prefix)} ${timeFmt().format(Date(epochMs))}"
        } else {
            monthDayFmt(context.getString(R.string.fmt_month_day_time)).format(Date(epochMs))
        }

    fun time(epochMs: Long): String = timeFmt().format(Date(epochMs))

    fun monthDayTime(context: android.content.Context, epochMs: Long): String =
        monthDayFmt(context.getString(R.string.fmt_month_day_time)).format(Date(epochMs))

    /** 带年份的完整本地化时刻(会议详情用,列表仍用短格式)。 */
    fun fullDateTimeLocalized(context: android.content.Context, epochMs: Long): String =
        monthDayFmt(context.getString(R.string.fmt_full_date_time)).format(Date(epochMs))

    fun fullDateTime(epochMs: Long): String = fullDateFmt().format(Date(epochMs))

    fun isToday(epochMs: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}
