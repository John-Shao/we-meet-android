package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.data.api.dto.CalendarExportRequest
import com.we.meet.data.api.dto.CalendarMemberDto
import com.we.meet.data.api.dto.CalendarMemberRequest
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.data.settings.CalendarDisplayMode
import com.we.meet.ui.components.DangerButton
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.SecondaryButton
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarManagementScreen(
    onBack: () -> Unit,
    onSubscribe: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: CalendarManagementViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val weekDays by app.settingsStore.calendarWeekVisibleDays.collectAsStateWithLifecycle()
    val displayMode by app.settingsStore.calendarDisplayMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.calendar_operation_failed)
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var colorTarget by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var unsubscribeTarget by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var exportTarget by remember { mutableStateOf<UnifiedCalendarDto?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(ui.error) { if (ui.error && ui.calendars.isNotEmpty()) snackbar.showSnackbar(errorText) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.calendar_manage_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.calendar_add))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = Dimens.ElevationSticky) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.calendar_settings_title)) },
                    leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CalendarModeStrip(
                current = displayMode,
                visibleDays = weekDays,
                onSelect = { mode ->
                    app.settingsStore.setCalendarDisplayMode(mode)
                    onBack()
                },
            )
            HorizontalDivider()
            when {
                ui.loading && ui.calendars.isEmpty() -> WeMeetLoading()
                ui.unavailable -> WeMeetEmptyState(
                    title = stringResource(R.string.calendar_manage_unavailable),
                    icon = Icons.Filled.CalendarMonth,
                )
                ui.error && ui.calendars.isEmpty() -> WeMeetErrorState(onRetry = vm::refresh)
                ui.calendars.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(R.string.calendar_manage_empty),
                    icon = Icons.Filled.CalendarMonth,
                    action = { TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.calendar_add)) } },
                )
                else -> CalendarManagementList(
                    calendars = ui.calendars,
                    busyIds = ui.busyIds,
                    onToggle = vm::setEnabled,
                    onMore = { actionTarget = it },
                )
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            SheetAction(
                icon = Icons.Filled.Groups,
                text = stringResource(R.string.calendar_add_subscribe),
            ) { showAdd = false; onSubscribe() }
            SheetAction(
                icon = Icons.Filled.Add,
                text = stringResource(R.string.calendar_add_new),
            ) { showAdd = false; onCreate() }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
    actionTarget?.let { calendar ->
        ModalBottomSheet(onDismissRequest = { actionTarget = null }) {
            Text(
                calendar.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            )
            actionsForCalendar(calendar).forEach { action ->
                val (icon, label) = when (action) {
                    CalendarManagementAction.SHOW_ONLY -> Icons.Filled.ViewDay to R.string.calendar_show_only
                    CalendarManagementAction.SHARE -> Icons.Filled.Link to R.string.calendar_share_action
                    CalendarManagementAction.COLOR -> Icons.Filled.Palette to R.string.calendar_change_color
                    CalendarManagementAction.SETTINGS -> Icons.Filled.Settings to R.string.calendar_settings_action
                    CalendarManagementAction.EXPORT -> Icons.AutoMirrored.Filled.EventNote to R.string.calendar_export_action
                    CalendarManagementAction.UNSUBSCRIBE -> Icons.Filled.Delete to R.string.calendar_unsubscribe
                }
                SheetAction(icon, stringResource(label)) {
                    actionTarget = null
                    when (action) {
                        CalendarManagementAction.SHOW_ONLY -> vm.showOnly(calendar)
                        CalendarManagementAction.SHARE -> onShare(calendar.id)
                        CalendarManagementAction.COLOR -> colorTarget = calendar
                        CalendarManagementAction.SETTINGS -> onEdit(calendar.id)
                        CalendarManagementAction.EXPORT -> exportTarget = calendar
                        CalendarManagementAction.UNSUBSCRIBE -> unsubscribeTarget = calendar
                    }
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
    colorTarget?.let { calendar ->
        CalendarColorSheet(
            selected = calendar.color,
            onDismiss = { colorTarget = null },
            onSelect = { color -> vm.setColor(calendar, color); colorTarget = null },
        )
    }
    unsubscribeTarget?.let { calendar ->
        AlertDialog(
            onDismissRequest = { unsubscribeTarget = null },
            title = { Text(stringResource(R.string.calendar_unsubscribe_confirm_title)) },
            text = { Text(stringResource(R.string.calendar_unsubscribe_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { vm.unsubscribe(calendar); unsubscribeTarget = null }) {
                    Text(stringResource(R.string.calendar_unsubscribe))
                }
            },
            dismissButton = {
                TextButton(onClick = { unsubscribeTarget = null }) {
                    Text(stringResource(R.string.calendar_cancel))
                }
            },
        )
    }
    exportTarget?.let { calendar ->
        CalendarExportDialog(calendar = calendar, onDismiss = { exportTarget = null })
    }
}

@Composable
private fun CalendarModeStrip(
    current: CalendarDisplayMode,
    visibleDays: Int,
    onSelect: (CalendarDisplayMode) -> Unit,
) {
    val entries = listOf(
        Triple(CalendarDisplayMode.AGENDA, Icons.AutoMirrored.Filled.EventNote, stringResource(R.string.calendar_view_agenda)),
        Triple(CalendarDisplayMode.DAY, Icons.Filled.ViewDay, stringResource(R.string.calendar_view_day)),
        Triple(CalendarDisplayMode.MULTI_DAY, Icons.Filled.ViewWeek, stringResource(R.string.calendar_view_multi_days, visibleDays)),
        Triple(CalendarDisplayMode.MONTH, Icons.Filled.CalendarMonth, stringResource(R.string.calendar_view_month)),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS)) {
        entries.forEach { (mode, icon, label) ->
            val selected = current == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    .clickable { onSelect(mode) }
                    .padding(vertical = Dimens.SpaceS),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CalendarManagementList(
    calendars: List<UnifiedCalendarDto>,
    busyIds: Set<String>,
    onToggle: (UnifiedCalendarDto, Boolean) -> Unit,
    onMore: (UnifiedCalendarDto) -> Unit,
) {
    val grouped = groupCalendars(calendars)
    val groups = listOf(
        stringResource(R.string.calendar_group_managed) to grouped.managed,
        stringResource(R.string.calendar_group_subscribed) to grouped.subscribed,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.SpaceXl),
    ) {
        groups.forEach { (title, rows) ->
            if (rows.isNotEmpty()) {
                item(key = "header-$title") {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = Dimens.ScreenPadding,
                            end = Dimens.ScreenPadding,
                            top = Dimens.SpaceL,
                            bottom = Dimens.SpaceS,
                        ),
                    )
                }
                items(rows, key = { it.id }) { calendar ->
                    ListItem(
                        headlineContent = {
                            Text(calendar.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = calendar.owner?.fullName?.takeIf { it.isNotBlank() }?.let { owner ->
                            { Text(owner, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = {
                            CalendarAvatar(calendar.displayName, calendar.color, enabled = calendar.enabled)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (calendar.id in busyIds) {
                                    CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall))
                                } else {
                                    Switch(
                                        checked = calendar.enabled,
                                        onCheckedChange = { onToggle(calendar, it) },
                                    )
                                }
                                IconButton(onClick = { onMore(calendar) }) {
                                    Icon(
                                        Icons.Filled.MoreHoriz,
                                        stringResource(R.string.calendar_manage_more, calendar.displayName),
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = Dimens.Calendar.FabClearance))
                }
            }
        }
    }
}

@Composable
fun CalendarAvatar(name: String, colorValue: String?, enabled: Boolean = true) {
    val color = parseCalendarColor(colorValue) ?: MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Dimens.AvatarM)
            .clip(CircleShape)
            .background(color.copy(alpha = if (enabled) 0.18f else 0.08f)),
    ) {
        Text(
            name.trim().take(1).ifBlank { "·" },
            color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarColorSheet(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.calendar_color_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        )
        CALENDAR_COLOR_PALETTE.chunked(6).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                row.forEach { value ->
                    val color = parseCalendarColor(value) ?: Color.Transparent
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(Dimens.MinTouchTarget)
                            .testTag("calendar-color-$value")
                            .clip(CircleShape)
                            .clickable { onSelect(value) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(Dimens.AvatarXs)
                                .clip(CircleShape)
                                .background(color),
                        )
                        if (value.equals(selected, ignoreCase = true)) {
                            Text("✓", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.SpaceXl))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDiscoverScreen(onBack: () -> Unit) {
    val vm: CalendarDiscoverViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tabs = CalendarDiscoverTab.entries
    val snackbar = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.calendar_operation_failed)
    LaunchedEffect(ui.error) {
        if (ui.error && ui.rows.isNotEmpty()) snackbar.showSnackbar(errorText)
    }
    Scaffold(
        topBar = {
            WeMeetTopBar(title = stringResource(R.string.calendar_subscribe_title), onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                label = { Text(stringResource(R.string.calendar_discover_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
            )
            TabRow(selectedTabIndex = ui.tab.ordinal) {
                tabs.forEach { tab ->
                    val label = when (tab) {
                        CalendarDiscoverTab.CONTACTS -> R.string.calendar_discover_contacts
                        CalendarDiscoverTab.ROOMS -> R.string.calendar_discover_rooms
                        CalendarDiscoverTab.PUBLIC -> R.string.calendar_discover_public
                    }
                    Tab(
                        selected = ui.tab == tab,
                        onClick = { vm.setTab(tab) },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
            when {
                ui.loading && ui.rows.isEmpty() -> WeMeetLoading()
                ui.error && ui.rows.isEmpty() -> WeMeetErrorState(onRetry = vm::retry)
                ui.rows.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(
                        when (ui.tab) {
                            CalendarDiscoverTab.CONTACTS -> R.string.calendar_discover_empty_contacts
                            CalendarDiscoverTab.ROOMS -> R.string.calendar_discover_empty_rooms
                            CalendarDiscoverTab.PUBLIC -> R.string.calendar_discover_empty_public
                        },
                    ),
                    icon = Icons.Filled.CalendarMonth,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.rows, key = { it.id }) { calendar ->
                        ListItem(
                            headlineContent = {
                                Text(calendar.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = calendar.meetingRoom?.code?.takeIf { it.isNotBlank() }?.let { code ->
                                { Text(stringResource(R.string.calendar_room_code, code)) }
                            } ?: calendar.owner?.fullName?.let { owner -> { Text(owner) } },
                            leadingContent = { CalendarAvatar(calendar.displayName, calendar.color) },
                            trailingContent = {
                                OutlinedButton(
                                    enabled = !calendar.subscribed && calendar.id !in ui.subscribingIds,
                                    onClick = { vm.subscribe(calendar) },
                                ) {
                                    if (calendar.id in ui.subscribingIds) {
                                        CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall))
                                    } else {
                                        Text(
                                            stringResource(
                                                if (calendar.subscribed) R.string.calendar_subscribed
                                                else R.string.calendar_subscribe,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = Dimens.Calendar.FabClearance))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarEditorScreen(calendarId: String?, onBack: () -> Unit, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: CalendarEditorViewModel = viewModel(
        factory = CalendarEditorViewModel.Factory(app, calendarId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val existing = ui.calendar
    var initialized by rememberSaveable(calendarId) { mutableStateOf(calendarId == null) }
    var name by rememberSaveable(calendarId) { mutableStateOf("") }
    var description by rememberSaveable(calendarId) { mutableStateOf("") }
    var access by rememberSaveable(calendarId) { mutableStateOf("details") }
    var color by rememberSaveable(calendarId) { mutableStateOf(CALENDAR_COLOR_PALETTE.first()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var colorOpen by rememberSaveable { mutableStateOf(false) }
    var accessOpen by rememberSaveable { mutableStateOf(false) }
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingMembers by remember { mutableStateOf<List<PickedMember>>(emptyList()) }
    var roleTarget by remember { mutableStateOf<CalendarMemberDto?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val saveErrorText = stringResource(R.string.calendar_save_failed)

    LaunchedEffect(existing?.id) {
        if (!initialized && existing != null) {
            name = existing.displayName
            description = existing.description
            access = existing.organizationDefaultAccess
            color = validCalendarColorOrDefault(existing.color)
            initialized = true
        }
    }
    LaunchedEffect(ui.completed) { if (ui.completed) onDone() }
    LaunchedEffect(ui.error) {
        if (ui.error && (calendarId == null || existing != null)) {
            snackbar.showSnackbar(saveErrorText)
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(
                    if (calendarId == null) R.string.calendar_add_new else R.string.calendar_settings_title,
                ),
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(tonalElevation = Dimens.ElevationSticky) {
                PrimaryButton(
                    text = stringResource(if (calendarId == null) R.string.calendar_create else R.string.calendar_save),
                    enabled = isCalendarFormValid(name) && initialized,
                    loading = ui.saving,
                    onClick = {
                        if (calendarId == null) {
                            vm.create(
                                name,
                                description,
                                color,
                                access,
                                pendingMembers.map { CalendarMemberRequest(it.userId, "details") },
                            )
                        } else {
                            vm.save(name, description, access, color)
                        }
                    },
                    modifier = Modifier.padding(Dimens.ScreenPadding),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            ui.loading -> WeMeetLoading(Modifier.padding(padding))
            ui.error && existing == null && calendarId != null -> WeMeetErrorState(
                onRetry = vm::load,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
            ) {
                item {
                    EditorSection(stringResource(R.string.calendar_editor_basic)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CalendarAvatar(name, color)
                            Spacer(Modifier.size(Dimens.SpaceM))
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text(stringResource(R.string.calendar_name)) },
                                    enabled = existing?.kind != "primary",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (existing?.kind == "primary") {
                                    Text(
                                        stringResource(R.string.calendar_editor_primary_name_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(R.string.calendar_description)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SettingsLikeRow(
                            label = stringResource(R.string.calendar_color_title),
                            value = "",
                            leading = { CalendarColorDot(color) },
                            onClick = { colorOpen = true },
                        )
                    }
                }
                item {
                    EditorSection(stringResource(R.string.calendar_editor_permissions)) {
                        SettingsLikeRow(
                            label = stringResource(R.string.calendar_editor_org_access),
                            value = accessLabel(access),
                            onClick = { accessOpen = true },
                        )
                        SettingsLikeRow(
                            label = stringResource(R.string.calendar_editor_external_access),
                            value = stringResource(R.string.calendar_editor_external_private),
                        )
                    }
                }
                item {
                    EditorSection(stringResource(R.string.calendar_editor_members)) {
                        ui.members.forEach { member ->
                            MemberRow(
                                name = member.user.fullName ?: member.user.shortName ?: member.user.id,
                                role = roleLabel(member.role),
                                onRole = { roleTarget = member },
                                onRemove = { vm.removeMember(member) },
                            )
                        }
                        pendingMembers.forEach { member ->
                            MemberRow(
                                name = member.displayName,
                                role = roleLabel("details"),
                                onRole = null,
                                onRemove = { pendingMembers = pendingMembers.filterNot { it.userId == member.userId } },
                            )
                        }
                        SecondaryButton(
                            text = stringResource(R.string.calendar_editor_add_members),
                            onClick = { picking = true },
                        )
                    }
                }
                if (existing?.capabilities?.canDelete == true) {
                    item {
                        DangerButton(
                            text = stringResource(R.string.calendar_delete_recoverable),
                            onClick = { deleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        ContactPicker(
            deps = app,
            mode = if (calendarId == null) ContactPickerMode.Multi else ContactPickerMode.Single,
            includeExternal = true,
            excludeUserIds = ui.members.mapTo(mutableSetOf()) { it.user.id } +
                pendingMembers.map { it.userId },
            onConfirm = { picked ->
                picking = false
                if (calendarId == null) {
                    pendingMembers = (pendingMembers + picked).distinctBy { it.userId }
                } else {
                    picked.firstOrNull()?.let { vm.addMember(it.userId, "details") }
                }
            },
            onDismiss = { picking = false },
        )
    }
    if (colorOpen) {
        CalendarColorSheet(
            selected = color,
            onDismiss = { colorOpen = false },
            onSelect = { color = it; colorOpen = false },
        )
    }
    if (accessOpen) {
        ChoiceSheet(
            title = stringResource(R.string.calendar_editor_org_access),
            choices = listOf("none", "free_busy", "details"),
            selected = access,
            label = { accessLabel(it) },
            onDismiss = { accessOpen = false },
            onSelect = { access = it; accessOpen = false },
        )
    }
    roleTarget?.let { member ->
        ChoiceSheet(
            title = member.user.fullName ?: member.user.shortName ?: member.user.id,
            choices = if (existing?.kind == "primary") listOf("free_busy", "details")
            else listOf("free_busy", "details", "writer", "admin"),
            selected = member.role,
            label = { roleLabel(it) },
            onDismiss = { roleTarget = null },
            onSelect = { vm.updateMember(member, it); roleTarget = null },
        )
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.calendar_delete_confirm_title)) },
            text = { Text(stringResource(R.string.calendar_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { deleteConfirm = false; vm.deleteCalendar() }) {
                    Text(stringResource(R.string.calendar_delete_recoverable))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.calendar_cancel)) }
            },
        )
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(Dimens.CornerM), tonalElevation = Dimens.ElevationSubtle) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceL),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsLikeRow(
    label: String,
    value: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Dimens.SpaceS),
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.size(Dimens.SpaceS))
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarColorDot(value: String) {
    Box(
        Modifier
            .size(Dimens.IconMedium)
            .clip(CircleShape)
            .background(parseCalendarColor(value) ?: MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun MemberRow(
    name: String,
    role: String,
    onRole: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Person, contentDescription = null)
        Text(
            name,
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceS),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { onRole?.invoke() }, enabled = onRole != null) { Text(role) }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, stringResource(R.string.calendar_remove))
        }
    }
}

@Composable
private fun accessLabel(value: String): String = stringResource(
    when (value) {
        "none" -> R.string.calendar_access_private
        "free_busy" -> R.string.calendar_access_free_busy
        else -> R.string.calendar_access_reader
    },
)

@Composable
private fun roleLabel(value: String): String = stringResource(
    when (value) {
        "free_busy" -> R.string.calendar_access_free_busy
        "writer" -> R.string.calendar_access_writer
        "admin" -> R.string.calendar_access_admin
        else -> R.string.calendar_access_reader
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    title: String,
    choices: List<String>,
    selected: String,
    label: @Composable (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        )
        choices.forEach { choice ->
            ListItem(
                headlineContent = { Text(label(choice)) },
                trailingContent = {
                    if (choice == selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable { onSelect(choice) },
            )
        }
        Spacer(Modifier.height(Dimens.SpaceXl))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarExportDialog(calendar: UnifiedCalendarDto, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf("week") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var pickingStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var submitting by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                listOf("today", "week", "month", "custom").forEach { value ->
                    val label = when (value) {
                        "today" -> R.string.calendar_range_today
                        "week" -> R.string.calendar_range_week
                        "month" -> R.string.calendar_range_month
                        else -> R.string.calendar_range_custom
                    }
                    OutlinedButton(onClick = { range = value }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (range == value) "✓ " else "") + stringResource(label))
                    }
                }
                if (range == "custom") {
                    TextButton(onClick = { pickingStart = true }) {
                        Text(stringResource(R.string.calendar_start_date) + " · " + start)
                    }
                    TextButton(onClick = { pickingStart = false }) {
                        Text(stringResource(R.string.calendar_end_date) + " · " + end)
                    }
                }
                Text(stringResource(R.string.calendar_export_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && (range != "custom" || !LocalDate.parse(end).isBefore(LocalDate.parse(start))),
                onClick = {
                    submitting = true
                    scope.launch {
                        runCatching {
                            app.apiClient.calendarApi.createCalendarExport(
                                calendar.id,
                                CalendarExportRequest(
                                    range = range,
                                    start = start.takeIf { range == "custom" },
                                    end = end.takeIf { range == "custom" },
                                    timezone = app.settingsStore.calendarZoneId().id,
                                ),
                            )
                        }
                        submitting = false
                        onDismiss()
                    }
                },
            ) { Text(stringResource(if (submitting) R.string.calendar_busy_saving else R.string.calendar_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) } },
    )
    pickingStart?.let { isStart ->
        val value = if (isStart) start else end
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(value)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingStart = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        if (isStart) start = date else end = date
                    }
                    pickingStart = null
                }) { Text(stringResource(R.string.calendar_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingStart = null }) { Text(stringResource(R.string.calendar_cancel)) }
            },
        ) { DatePicker(state) }
    }
}
