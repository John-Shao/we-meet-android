package com.we.meet.feature.docs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.renderer.DocOutlineEntry
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

@Composable
internal fun DocOutlineDrawer(
    entries: List<DocOutlineEntry>,
    activeKey: String?,
    drawerState: DrawerState,
    onSelect: (DocOutlineEntry) -> Unit,
    content: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    fun close() { scope.launch { drawerState.close() } }
    BackHandler(enabled = drawerState.isOpen) { close() }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            val index = entries.indexOfFirst { it.key == activeKey }
            if (index >= 0) listState.scrollToItem(index)
        }
    }
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) drawerState.close()
    }
    // Material's start drawer opens on the physical right in RTL. Restore the
    // document's direction inside both surfaces so text and reader stay unchanged.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        key(drawerState) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                DocNavigationDrawerSheet {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        Row(Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.docs_outline), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = ::close) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.docs_cancel))
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.weight(1f), state = listState,
                            contentPadding = PaddingValues(vertical = Dimens.SpaceS)) {
                            items(entries, key = { it.key }) { entry ->
                                val selected = entry.key == activeKey
                                Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Text(entry.title,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget)
                                            .selectable(selected, role = Role.Button, onClick = { onSelect(entry) })
                                            .padding(start = Dimens.ScreenPadding + Dimens.SpaceM * (entry.level - 1),
                                                end = Dimens.ScreenPadding, top = Dimens.SpaceM, bottom = Dimens.SpaceM),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            },
            content = { CompositionLocalProvider(LocalLayoutDirection provides direction, content = content) },
        )
        }
    }
}
