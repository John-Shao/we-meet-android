@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.we.meet.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

@Composable
internal fun MeetingMaterialsLinks(onOpenRecords: () -> Unit, onOpenMinutes: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.meeting_materials_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.meeting_materials_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            TextButton(onClick = onOpenRecords) { Text(stringResource(R.string.records_title)) }
            TextButton(onClick = onOpenMinutes) { Text(stringResource(R.string.records_minutes)) }
        }
    }
}
