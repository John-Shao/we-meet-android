package com.we.meet.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

enum class MeetingSection(val title: Int) {
    VIDEO(R.string.meeting_video),
    RECORDING(R.string.home_ai_recording),
    RECORDS(R.string.records_title),
    MINUTES(R.string.records_minutes);

    companion object {
        fun available(captureEnabled: Boolean, recordsEnabled: Boolean) = entries.filter {
            when (it) {
                VIDEO -> true
                RECORDING -> captureEnabled
                RECORDS, MINUTES -> recordsEnabled
            }
        }

        fun restore(name: String?, captureEnabled: Boolean, recordsEnabled: Boolean): MeetingSection =
            available(captureEnabled, recordsEnabled).find { it.name == name } ?: VIDEO
    }
}

@Composable
fun MeetingNavigationDrawer(
    selected: MeetingSection,
    sections: List<MeetingSection>,
    onSelect: (MeetingSection) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(Dimens.SpaceXl), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.ListLeadingIcon)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Videocam, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Text(stringResource(R.string.tab_meeting), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.meeting_navigation_close))
            }
        }
        sections.forEach { section ->
            NavigationDrawerItem(
                modifier = Modifier.padding(horizontal = Dimens.SpaceM).testTag("meeting-section-${section.name}"),
                shape = MaterialTheme.shapes.medium,
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
                selected = section == selected,
                onClick = { onSelect(section) },
                label = { Text(stringResource(section.title)) },
                icon = { Icon(when (section) {
                    MeetingSection.VIDEO -> Icons.Outlined.Videocam
                    MeetingSection.RECORDING -> Icons.Outlined.Mic
                    MeetingSection.RECORDS -> Icons.Outlined.Description
                    MeetingSection.MINUTES -> Icons.Outlined.AutoAwesome
                }, null) },
            )
        }
    }
}
