package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

enum class CalendarPrimaryPage { CALENDAR, MEETING_ROOMS }

@Composable
fun CalendarPrimaryToolbar(
    current: CalendarPrimaryPage,
    onSelect: (CalendarPrimaryPage) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.SpaceM, end = Dimens.SpaceXs, top = Dimens.SpaceS),
    ) {
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(Dimens.CornerS),
                )
                .padding(Dimens.SpaceXxs),
        ) {
            CalendarPrimaryPage.entries.forEach { page ->
                val selected = current == page
                Text(
                    text = stringResource(
                        if (page == CalendarPrimaryPage.CALENDAR) R.string.tab_calendar
                        else R.string.meeting_rooms_title,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.CornerXs))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        )
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clickable { onSelect(page) }
                        .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.calendar_settings_title),
            )
        }
    }
}
