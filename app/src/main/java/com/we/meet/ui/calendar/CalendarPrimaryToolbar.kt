package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.ui.calendar.views.CalendarViewMode
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

enum class CalendarPrimaryPage { CALENDAR, MEETING_ROOMS }

internal fun calendarManagementIcon(viewMode: CalendarViewMode): ImageVector = when (viewMode) {
    CalendarViewMode.AGENDA -> Icons.AutoMirrored.Filled.EventNote
    CalendarViewMode.DAY -> Icons.Filled.ViewDay
    CalendarViewMode.WEEK -> Icons.Filled.ViewWeek
    CalendarViewMode.MONTH -> Icons.Filled.CalendarMonth
}

/** Stable primary navigation: two equal tabs plus one fixed calendar-management action. */
@Composable
fun CalendarPrimaryToolbar(
    current: CalendarPrimaryPage,
    onSelect: (CalendarPrimaryPage) -> Unit,
    onOpenManagement: () -> Unit,
    calendarViewMode: CalendarViewMode = CalendarViewMode.AGENDA,
) {
    val actionIsSettings = current == CalendarPrimaryPage.MEETING_ROOMS
    val calendarColors = WeMeetTheme.extras.calendar
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceS),
        ) {
            CalendarPrimaryPage.entries.forEach { page ->
                val selected = current == page
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("calendar-primary-${page.name.lowercase()}")
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clickable { onSelect(page) }
                        .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceS),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                if (page == CalendarPrimaryPage.CALENDAR) R.string.tab_calendar
                                else R.string.meeting_rooms_title,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Spacer(Modifier.height(Dimens.SpaceXs))
                        HorizontalDivider(
                            thickness = Dimens.BorderEmphasis,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else calendarColors.gridLine,
                        )
                    }
                }
            }
            IconButton(
                onClick = onOpenManagement,
                modifier = Modifier.testTag(
                    if (actionIsSettings) "calendar-settings" else "calendar-manage",
                ),
            ) {
                Icon(
                    if (actionIsSettings) Icons.Filled.Settings
                    else calendarManagementIcon(calendarViewMode),
                    contentDescription = stringResource(
                        if (actionIsSettings) R.string.calendar_settings_title
                        else R.string.calendar_manage_open,
                    ),
                    modifier = Modifier.testTag(
                        if (actionIsSettings) "calendar-settings-icon"
                        else "calendar-manage-icon-${calendarViewMode.name.lowercase()}",
                    ),
                )
            }
        }
        HorizontalDivider(color = calendarColors.gridLine)
    }
}
