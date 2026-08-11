package com.we.meet.ui.calendar.views

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Format a UTC offset expressed as minutes east of UTC. */
internal fun formatGmtOffset(offsetMinutes: Int): String {
    val sign = if (offsetMinutes >= 0) "+" else "-"
    val absoluteMinutes = kotlin.math.abs(offsetMinutes)
    val hours = absoluteMinutes / 60
    val minutes = absoluteMinutes % 60
    val minuteSuffix = if (minutes == 0) "" else ":${minutes.toString().padStart(2, '0')}"
    return "GMT$sign$hours$minuteSuffix"
}

/**
 * Resolve the device timezone offset for the displayed date. Noon avoids edge cases around
 * daylight-saving transitions at midnight.
 */
internal fun calendarTimeZoneLabel(
    date: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val offsetMinutes = date
        .atTime(LocalTime.NOON)
        .atZone(zoneId)
        .offset
        .totalSeconds / 60
    return formatGmtOffset(offsetMinutes)
}

@Composable
internal fun CalendarTimeZoneHeader(date: LocalDate) {
    Text(
        text = calendarTimeZoneLabel(date),
        style = WeMeetTextStyles.LabelTiny,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimens.SpaceXs),
    )
}
