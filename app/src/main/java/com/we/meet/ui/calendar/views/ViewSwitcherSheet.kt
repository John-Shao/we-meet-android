package com.we.meet.ui.calendar.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** P8:视图切换滑窗(日程/日/周/月),当前项打勾。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSwitcherSheet(
    current: CalendarViewMode,
    onSelect: (CalendarViewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CalendarViewMode.entries.forEach { mode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = mode.icon(),
                    contentDescription = null,
                    tint = if (mode == current) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(mode.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mode == current) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (mode == current) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

internal fun CalendarViewMode.icon(): ImageVector = when (this) {
    CalendarViewMode.AGENDA -> Icons.Filled.ViewAgenda
    CalendarViewMode.DAY -> Icons.Filled.CalendarViewDay
    CalendarViewMode.WEEK -> Icons.Filled.CalendarViewWeek
    CalendarViewMode.MONTH -> Icons.Filled.CalendarMonth
}
