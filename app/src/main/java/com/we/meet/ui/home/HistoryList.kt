package com.we.meet.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.history.HistoryEntry
import com.we.meet.ui.locale.appLocale
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
        MeetingListSectionTitle(stringResource(R.string.history_section_title))
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
    MeetingListItem(
        title = entry.name.ifBlank { entry.slug },
        timestamp = HistoryTimeFormatter.fullDateTimeLocalized(
            LocalContext.current,
            entry.firstJoinedAtMs.takeIf { it > 0 } ?: entry.createdAtMs,
        ),
        icon = Icons.Outlined.Videocam,
        onClick = onClick,
    )
}

object HistoryTimeFormatter {
    // "HH:mm" 这类纯数字 pattern 的输出与 UI 语言无关,保持设备 locale;
    // 下面 monthDayFmt 的 pattern 来自资源串(含月份名/年份写法),必须用应用内
    // 语言 —— 否则应用切英文 + 系统中文会渲染成「9月 11, 10:05」。
    private fun timeFmt() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun monthDayFmt(pattern: String, locale: Locale) = SimpleDateFormat(pattern, locale)
    private fun fullDateFmt() = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())

    /** "<today> HH:mm" if same calendar day, else the localized month-day-time.
     *  Both the today prefix and the month-day pattern are localized, so
     *  callers pass a [Context]. */
    fun relativeListTimestamp(context: android.content.Context, epochMs: Long): String =
        if (isToday(epochMs)) {
            "${context.getString(R.string.history_today_prefix)} ${timeFmt().format(Date(epochMs))}"
        } else {
            monthDayFmt(context.getString(R.string.fmt_month_day_time), context.appLocale())
                .format(Date(epochMs))
        }

    fun time(epochMs: Long): String = timeFmt().format(Date(epochMs))

    fun monthDayTime(context: android.content.Context, epochMs: Long): String =
        monthDayFmt(context.getString(R.string.fmt_month_day_time), context.appLocale())
            .format(Date(epochMs))

    /** 带年份的完整本地化时刻(会议详情用,列表仍用短格式)。 */
    fun fullDateTimeLocalized(context: android.content.Context, epochMs: Long): String =
        monthDayFmt(context.getString(R.string.fmt_full_date_time), context.appLocale())
            .format(Date(epochMs))

    fun fullDateTime(epochMs: Long): String = fullDateFmt().format(Date(epochMs))

    fun isToday(epochMs: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = epochMs }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}
