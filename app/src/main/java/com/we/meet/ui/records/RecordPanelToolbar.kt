package com.we.meet.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.ui.theme.Dimens

internal data class RecordToolAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** One visible primary action; secondary actions remain reachable at large font sizes. */
@Composable
internal fun RecordPanelToolbar(actions: List<RecordToolAction>, leading: (@Composable RowScope.() -> Unit)? = null, showPrimaryWithLeading: Boolean = false) {
    if (actions.isEmpty() && leading == null) return
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading()
            if (leading == null || showPrimaryWithLeading) actions.firstOrNull()?.let { action ->
                TextButton(onClick = action.onClick, enabled = action.enabled,
                    modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget)) {
                    Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val secondary = if (leading == null || showPrimaryWithLeading) actions.drop(1) else actions
            if (secondary.isNotEmpty()) Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.records_panel_actions))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    secondary.forEach { action ->
                        DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled,
                            onClick = { expanded = false; action.onClick() })
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = Dimens.DividerThin)
    }
}
