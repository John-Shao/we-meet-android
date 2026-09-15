@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.we.meet.ui.records

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.R
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordScope
import com.we.meet.data.repository.RecordSource
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.components.*
import com.we.meet.ui.theme.Dimens

@Composable
fun RecordLibraryScreen(
    repository: MeetingRecordRepository,
    viewer: String,
    summariesOnly: Boolean,
    onRecord: (String) -> Unit,
    onBack: () -> Unit,
    onOpenNavDrawer: (() -> Unit)? = null,
    onStartRecording: (() -> Unit)? = null,
    uploadRepository: RecordingUploadRepository? = null,
) {
    var scope by remember(viewer) { mutableStateOf(RecordScope.RECENT) }
    var source by remember(viewer) { mutableStateOf<RecordSource?>(null) }
    var input by remember(viewer) { mutableStateOf("") }
    var query by remember(viewer) { mutableStateOf("") }
    var searchVisible by remember(viewer) { mutableStateOf(false) }
    var filtersVisible by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var cursors by remember(viewer, scope, source, query, summariesOnly) { mutableStateOf(listOf<String?>(null)) }
    var refresh by remember { mutableIntStateOf(0) }
    val cursor = cursors.last()
    val listState = remember(viewer, scope, source, query, cursor, summariesOnly) { LazyGridState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val search = { query = input.trim(); refresh++; keyboard?.hide(); Unit }
    val result = visibleRead(viewer, scope, source, query, cursor, summariesOnly, refresh) {
        repository.records(viewer, scope, source, summariesOnly, query.ifBlank { null }, cursor)
    }
    LaunchedEffect(searchVisible) { if (searchVisible) focus.requestFocus() }
    Scaffold(
        topBar = {
            WeMeetTopBar(stringResource(if (summariesOnly) R.string.records_minutes else R.string.records_title),
                onBack = if (onOpenNavDrawer == null) onBack else null,
                onMenu = onOpenNavDrawer, menuDescription = stringResource(R.string.meeting_navigation),
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) { input = ""; query = "" } }) {
                        Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searchVisible) R.string.records_clear_search else R.string.records_search))
                    }
                    IconButton(onClick = { filtersVisible = true }) {
                        Icon(Icons.Outlined.Tune, stringResource(R.string.records_filters),
                            tint = if (source != null || scope == RecordScope.PARTICIPATED) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                })
        },
        bottomBar = {
            if (!summariesOnly && (uploadRepository != null || onStartRecording != null)) {
                Surface(shadowElevation = Dimens.ElevationOverlay) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
                        if (uploadRepository != null) RecordingUploadAction(uploadRepository, viewer, onRecord, Modifier.weight(1f))
                        if (onStartRecording != null) Button(onClick = onStartRecording, modifier = Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget), shape = CircleShape) {
                            Icon(Icons.Outlined.Mic, null, Modifier.size(Dimens.ComponentIconMedium))
                            Spacer(Modifier.width(Dimens.SpaceS))
                            Text(stringResource(R.string.records_start_recording))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    listOf(RecordScope.RECENT, RecordScope.OWNED, RecordScope.SHARED).forEach { value ->
                        FilterChip(selected = scope == value, onClick = { scope = value }, shape = CircleShape,
                            border = null, colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                            label = { Text(stringResource(scopeLabel(value)), fontWeight = if (scope == value) FontWeight.SemiBold else FontWeight.Normal) })
                    }
                }
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                        stringResource(if (grid) R.string.records_list_view else R.string.records_grid_view))
                }
            }
            if (searchVisible) OutlinedTextField(input, onValueChange = { input = it.take(200); if (input.isEmpty()) query = "" },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding).focusRequester(focus), singleLine = true,
                placeholder = { Text(stringResource(R.string.records_search)) }, shape = MaterialTheme.shapes.large,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                    TextButton(onClick = search) { Text(stringResource(R.string.records_search_action)) }
                }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
            if (source != null || scope == RecordScope.PARTICIPATED) Row(Modifier.padding(horizontal = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(source?.let { stringResource(sourceLabel(it.wire)) },
                    if (scope == RecordScope.PARTICIPATED) stringResource(R.string.records_participated) else null).joinToString(" · "),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { source = null; scope = RecordScope.RECENT }) { Text(stringResource(R.string.records_reset_filters)) }
            }
            when {
                result == null -> WeMeetInlineLoading()
                result.isFailure -> WeMeetErrorState(onRetry = { refresh++ }, message = stringResource(R.string.records_unavailable))
                result.getOrThrow().results.isEmpty() -> WeMeetEmptyState(stringResource(R.string.records_empty),
                    description = stringResource(R.string.records_empty_hint),
                    action = { TextButton(onClick = { cursors = listOf(null); refresh++ }) { Text(stringResource(R.string.records_refresh)) } })
                else -> {
                    val page = result.getOrThrow()
                    LazyVerticalGrid(columns = if (grid) GridCells.Adaptive(Dimens.RecordGridMinWidth) else GridCells.Fixed(1), state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(Dimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                        items(page.results, key = { it.id }) { record ->
                            Card(onClick = { onRecord(record.id) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.padding(Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                    if (!grid) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Icon(when (record.sourceType) { "meeting" -> Icons.Outlined.Videocam; "upload" -> Icons.Outlined.UploadFile; else -> Icons.Outlined.GraphicEq },
                                            null, Modifier.padding(Dimens.SpaceM).size(Dimens.IconMedium), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                        Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(recordTime(record.originAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(stringResource(sourceLabel(record.sourceType)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (record.isOngoing || record.hasSummary) Text(stringResource(if (record.isOngoing) R.string.records_ongoing else R.string.records_minutes_ready),
                                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text(stringResource(R.string.records_previous)) }
                                page.nextCursor?.let { next -> TextButton(onClick = { cursors = cursors + next }) { Text(stringResource(R.string.records_next)) } }
                                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.records_refresh)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (filtersVisible) ModalBottomSheet(onDismissRequest = { filtersVisible = false }) {
        Column(Modifier.fillMaxWidth().padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Text(stringResource(R.string.records_filters), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                RecordScope.entries.forEach { value -> FilterChip(scope == value, onClick = { scope = value }, label = { Text(stringResource(scopeLabel(value))) }) }
            }
            Text(stringResource(R.string.records_source_filter), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                (listOf(null) + RecordSource.entries).forEach { value -> FilterChip(source == value, onClick = { source = value }, label = { Text(stringResource(sourceLabel(value?.wire))) }) }
            }
            Button(onClick = { filtersVisible = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.records_filters_done)) }
        }
    }
}
