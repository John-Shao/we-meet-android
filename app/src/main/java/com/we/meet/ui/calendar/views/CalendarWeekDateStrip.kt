package com.we.meet.ui.calendar.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.ui.theme.Dimens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Shared seven-day strip for calendar day view and meeting-room discovery. */
@Composable
internal fun CalendarWeekDateStrip(
    selectedDate: LocalDate,
    firstDayOfWeek: DayOfWeek,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    eventIndicatorColor: (LocalDate) -> Color? = { null },
    onWeekSwipe: ((Long) -> Unit)? = null,
) {
    val days = remember(selectedDate, firstDayOfWeek) {
        weekColumnDays(selectedDate, firstDayOfWeek)
    }
    val firstDate = days.first()
    val swipeThresholdPx = with(LocalDensity.current) { Dimens.MinTouchTarget.toPx() }
    val swipeModifier = if (onWeekSwipe != null) {
        Modifier.horizontalDateSwipe(
            enabled = true,
            gestureKey = firstDate,
            thresholdPx = swipeThresholdPx,
            onSwipe = onWeekSwipe,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(swipeModifier)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        days.forEach { date ->
            val selected = date == selectedDate
            val isToday = date == today
            val indicatorColor = eventIndicatorColor(date)
            val dateDescription = DateTimeFormatter
                .ofLocalizedDate(FormatStyle.FULL)
                .withLocale(Locale.getDefault())
                .format(date)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    .clickable { onSelectDate(date) }
                    .semantics {
                        role = Role.Button
                        contentDescription = dateDescription
                    }
                    .padding(vertical = Dimens.SpaceXs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = date.dayOfWeek.getDisplayName(
                        TextStyle.NARROW_STANDALONE,
                        Locale.getDefault(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Box(
                    modifier = Modifier
                        .padding(top = Dimens.SpaceXxs)
                        .size(Dimens.Calendar.DateCellSize)
                        .clip(CircleShape)
                        .background(
                            when {
                                selected -> MaterialTheme.colorScheme.primary
                                isToday -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            selected -> MaterialTheme.colorScheme.onPrimary
                            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (indicatorColor != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = Dimens.BorderThin)
                                .size(Dimens.Calendar.EventDotSize)
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        indicatorColor
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}
