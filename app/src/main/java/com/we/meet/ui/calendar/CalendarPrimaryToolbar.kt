package com.we.meet.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

enum class CalendarPrimaryPage { CALENDAR, MEETING_ROOMS }

/** Feishu-like page tabs translated to Material: text tabs with a thin active indicator. */
@Composable
fun CalendarPrimaryToolbar(
    current: CalendarPrimaryPage,
    onSelect: (CalendarPrimaryPage) -> Unit,
    onOpenManagement: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS),
        ) {
            CalendarPrimaryPage.entries.forEach { page ->
                val selected = current == page
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clickable { onSelect(page) }
                        .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                if (page == CalendarPrimaryPage.CALENDAR) R.string.tab_calendar
                                else R.string.meeting_rooms_title,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Spacer(Modifier.height(Dimens.SpaceXs))
                        HorizontalDivider(
                            thickness = Dimens.BorderEmphasis,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenManagement) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.calendar_manage_open),
                )
            }
        }
        HorizontalDivider()
    }
}
