package com.we.meet.ui.calendar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.data.api.dto.CalendarExportRequest
import com.we.meet.data.api.dto.CalendarMemberRequest
import com.we.meet.data.api.dto.CalendarSubscriptionRequest
import com.we.meet.data.api.dto.CreateCalendarRequest
import com.we.meet.data.api.dto.ExternalAuthorizeRequest
import com.we.meet.data.api.dto.ExternalCalendarAccountDto
import com.we.meet.data.api.dto.ProviderCalendarDto
import com.we.meet.data.api.dto.SelectProviderCalendarsRequest
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.data.api.dto.UpdateCalendarRequest
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.vm.ConversationListViewModel
import com.we.meet.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException

private enum class ManagementDialog { ADD, EXTERNAL }

/** Mobile counterpart of the Web calendar sidebar and its action menus. */
@Composable
fun UnifiedCalendarManagementSection() {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var calendars by remember { mutableStateOf<List<UnifiedCalendarDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<ManagementDialog?>(null) }
    var settings by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var sharing by remember { mutableStateOf<UnifiedCalendarDto?>(null) }
    var exporting by remember { mutableStateOf<UnifiedCalendarDto?>(null) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { api.listCalendars() }
            .onSuccess { calendars = it; error = false }
            .onFailure {
                unavailable = it is HttpException && it.code() == 404
                error = !unavailable
            }
        loading = false
    }
    fun mutate(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { error = true } }
    }

    if (unavailable) return

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.calendar_management_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { dialog = ManagementDialog.ADD }) { Text(stringResource(R.string.calendar_add)) }
        }
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall))
            error && calendars.isEmpty() -> TextButton(onClick = { reload++ }) { Text(stringResource(R.string.calendar_load_failed_retry)) }
            else -> {
                val groups = listOf(
                    stringResource(R.string.calendar_group_managed) to calendars.filter { it.capabilities.canManage && it.kind != "external" },
                    stringResource(R.string.calendar_group_subscribed) to calendars.filter { !it.capabilities.canManage && it.kind != "external" },
                    stringResource(R.string.calendar_group_external) to calendars.filter { it.kind == "external" },
                )
                groups.forEach { (title, rows) ->
                    if (rows.isNotEmpty()) {
                        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Dimens.SpaceS))
                        rows.forEach { calendar ->
                            CalendarManagementRow(
                                calendar = calendar,
                                onToggle = { enabled ->
                                    mutate {
                                        api.updateCalendarSubscription(
                                            calendar.id,
                                            CalendarSubscriptionRequest(enabled, calendar.color),
                                        )
                                    }
                                },
                                onOnly = {
                                    mutate {
                                        calendars.forEach { row ->
                                            api.updateCalendarSubscription(
                                                row.id,
                                                CalendarSubscriptionRequest(row.id == calendar.id, row.color),
                                            )
                                        }
                                    }
                                },
                                onSettings = { settings = calendar },
                                onShare = { sharing = calendar },
                                onExport = { exporting = calendar },
                            )
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { dialog = ManagementDialog.EXTERNAL }) {
            Text(stringResource(R.string.calendar_manage_external))
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpaceS))
    }

    if (dialog == ManagementDialog.ADD) {
        AddCalendarDialog(onDismiss = { dialog = null }, onChanged = { reload++ })
    }
    if (dialog == ManagementDialog.EXTERNAL) {
        ExternalCalendarsDialog(onDismiss = { dialog = null }, onChanged = { reload++ })
    }
    settings?.let { calendar ->
        UnifiedCalendarSettingsDialog(calendar, { settings = null }) { reload++ }
    }
    sharing?.let { calendar -> CalendarShareDialog(calendar) { sharing = null } }
    exporting?.let { calendar -> CalendarExportDialog(calendar) { exporting = null } }
}

@Composable
private fun CalendarManagementRow(
    calendar: UnifiedCalendarDto,
    onToggle: (Boolean) -> Unit,
    onOnly: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = Dimens.ElevationSticky, shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(calendar.displayName, modifier = Modifier.weight(1f), maxLines = 1)
                Switch(checked = calendar.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                TextButton(onClick = onOnly) { Text(stringResource(R.string.calendar_show_only)) }
                if (calendar.capabilities.canManage) TextButton(onClick = onSettings) { Text(stringResource(R.string.calendar_settings_action)) }
                if (calendar.capabilities.canShare) TextButton(onClick = onShare) { Text(stringResource(R.string.calendar_share_action)) }
                if (calendar.capabilities.canExport) TextButton(onClick = onExport) { Text(stringResource(R.string.calendar_export_action)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCalendarDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("contact") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UnifiedCalendarDto>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var access by remember { mutableStateOf("details") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(mode, query) {
        if (mode == "new") return@LaunchedEffect
        delay(250)
        results = runCatching { api.discoverCalendars(mode, query.trim()) }.getOrDefault(emptyList())
    }
    FullCalendarDialog(stringResource(R.string.calendar_add), onDismiss) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                "contact" to stringResource(R.string.calendar_discover_contacts),
                "room" to stringResource(R.string.calendar_discover_rooms),
                "public" to stringResource(R.string.calendar_discover_public),
                "new" to stringResource(R.string.calendar_discover_new),
            )
                .forEach { (value, label) ->
                    TextButton(onClick = { mode = value }, enabled = mode != value) { Text(label) }
                }
        }
        if (mode == "new") {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.calendar_name)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.calendar_description)) }, modifier = Modifier.fillMaxWidth())
            AccessSelector(access, listOf("none", "free_busy", "details")) { access = it }
            Button(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            api.createCalendar(CreateCalendarRequest(name.trim(), description.trim(), organizationDefaultAccess = access))
                        }.onSuccess { onChanged(); onDismiss() }.onFailure { error = true }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.calendar_create)) }
        } else {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.calendar_search)) }, modifier = Modifier.fillMaxWidth())
            results.forEach { calendar ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(calendar.displayName, modifier = Modifier.weight(1f))
                    Button(
                        enabled = !calendar.subscribed,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    api.updateCalendarSubscription(calendar.id, CalendarSubscriptionRequest())
                                }.onSuccess { onChanged() }.onFailure { error = true }
                            }
                        },
                    ) { Text(stringResource(if (calendar.subscribed) R.string.calendar_subscribed else R.string.calendar_subscribe)) }
                }
            }
        }
        if (busy) CircularProgressIndicator()
        if (error) Text(stringResource(R.string.calendar_operation_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun UnifiedCalendarSettingsDialog(
    calendar: UnifiedCalendarDto,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(calendar.displayName) }
    var description by remember { mutableStateOf(calendar.description) }
    var access by remember { mutableStateOf(calendar.organizationDefaultAccess) }
    var members by remember { mutableStateOf(emptyList<com.we.meet.data.api.dto.CalendarMemberDto>()) }
    var addRole by remember { mutableStateOf("details") }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(calendar.id) {
        members = runCatching { api.listCalendarMembers(calendar.id) }.getOrDefault(emptyList())
    }
    FullCalendarDialog(stringResource(R.string.calendar_settings_title), onDismiss) {
        OutlinedTextField(
            name,
            { name = it },
            readOnly = calendar.kind == "primary",
            label = { Text(stringResource(R.string.calendar_name_short)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.calendar_description)) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.calendar_external_private))
        AccessSelector(access, listOf("none", "free_busy", "details")) { access = it }
        Button(onClick = {
            scope.launch {
                runCatching {
                    api.updateCalendar(
                        calendar.id,
                        UpdateCalendarRequest(
                            name = name.takeIf { calendar.kind == "shared" },
                            description = description,
                            organizationDefaultAccess = access,
                        ),
                    )
                }.onSuccess { onChanged(); onDismiss() }.onFailure { error = true }
            }
        }) { Text(stringResource(R.string.calendar_save)) }
        Text(stringResource(R.string.calendar_members), style = MaterialTheme.typography.titleMedium)
        members.forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(member.user.fullName ?: member.user.shortName ?: member.user.id, modifier = Modifier.weight(1f))
                Text(member.role)
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.deleteCalendarMember(calendar.id, member.id) }
                            .onSuccess { members = members.filterNot { it.id == member.id }; onChanged() }
                    }
                }) { Text(stringResource(R.string.calendar_remove)) }
            }
        }
        AccessSelector(
            addRole,
            if (calendar.kind == "primary") listOf("free_busy", "details")
            else listOf("free_busy", "details", "writer", "admin"),
        ) { addRole = it }
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.calendar_add_member)) }
        if (calendar.capabilities.canDelete) {
            TextButton(onClick = {
                scope.launch {
                    runCatching { api.deleteCalendar(calendar.id) }
                        .onSuccess { onChanged(); onDismiss() }.onFailure { error = true }
                }
            }) { Text(stringResource(R.string.calendar_delete_recoverable), color = MaterialTheme.colorScheme.error) }
        }
        if (error) Text(stringResource(R.string.calendar_save_failed), color = MaterialTheme.colorScheme.error)
    }
    if (picking) {
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Single,
            includeExternal = true,
            excludeUserIds = members.mapTo(mutableSetOf()) { it.user.id },
            onConfirm = { picked ->
                picking = false
                val person = picked.firstOrNull() ?: return@ContactPicker
                scope.launch {
                    runCatching { api.addCalendarMember(calendar.id, CalendarMemberRequest(person.userId, addRole)) }
                        .onSuccess { members = members + it; onChanged() }
                        .onFailure { error = true }
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CalendarShareDialog(calendar: UnifiedCalendarDto, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<com.we.meet.data.api.dto.CalendarShareLinkDto?>(null) }
    var chooseChat by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(calendar.id) { link = runCatching { api.getCalendarShareLink(calendar.id) }.getOrNull() }
    FullCalendarDialog(stringResource(R.string.calendar_share_title), onDismiss) {
        Text(stringResource(R.string.calendar_share_hint))
        val current = link
        if (current == null) CircularProgressIndicator() else {
            val qr = remember(current.url) { generateQr(current.url) }
            qr?.let { Image(it.asImageBitmap(), stringResource(R.string.calendar_share_qr), modifier = Modifier.size(Dimens.Room.QrSize)) }
            OutlinedTextField(current.url, {}, readOnly = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                Button(onClick = { copyText(context, current.url) }) { Text(stringResource(R.string.calendar_copy_link)) }
                OutlinedButton(onClick = { chooseChat = true }) { Text(stringResource(R.string.calendar_share_to_conversation)) }
            }
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { api.resetCalendarShareLink(calendar.id) }
                        .onSuccess { link = it }.onFailure { error = true }
                }
            }) { Text(stringResource(R.string.calendar_reset_link)) }
        }
        if (error) Text(stringResource(R.string.calendar_operation_failed), color = MaterialTheme.colorScheme.error)
    }
    if (chooseChat && link != null) {
        CalendarConversationPicker(calendar, link!!.url) { chooseChat = false }
    }
}

@Composable
private fun CalendarConversationPicker(
    calendar: UnifiedCalendarDto,
    url: String,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: ConversationListViewModel = viewModel(factory = ConversationListViewModel.Factory(app))
    val rows by vm.rows.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    FullCalendarDialog(stringResource(R.string.calendar_choose_conversation), onDismiss) {
        val unnamedConversation = stringResource(R.string.calendar_unnamed_conversation)
        rows.forEach { row ->
            Text(
                row.title.ifBlank { unnamedConversation },
                modifier = Modifier.fillMaxWidth().clickable {
                    val body = JSONObject()
                        .put("v", 1)
                        .put("calendar_id", calendar.id)
                        .put("name", calendar.displayName)
                        .put("owner_name", calendar.owner?.fullName.orEmpty())
                        .put("description", calendar.description)
                        .put("subscriber_count", calendar.subscriberCount)
                        .put("subscribe_url", url)
                        .toString()
                    ImSession.get(app).sendMessageAsync(row.cid, body, "calendar-card")
                    onDismiss()
                }.padding(vertical = Dimens.SpaceM),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CalendarExportDialog(calendar: UnifiedCalendarDto, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf("week") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf(LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_export_title)) },
        text = {
            Column {
                AccessSelector(range, listOf("today", "week", "month", "custom")) { range = it }
                if (range == "custom") {
                    CalendarDateField(stringResource(R.string.calendar_start_date), start) { start = it }
                    CalendarDateField(stringResource(R.string.calendar_end_date), end) { end = it }
                }
                Text(stringResource(R.string.calendar_export_hint))
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        app.apiClient.calendarApi.createCalendarExport(
                            calendar.id,
                            CalendarExportRequest(
                                range = range,
                                start = start.takeIf { range == "custom" },
                                end = end.takeIf { range == "custom" },
                                timezone = ZoneId.systemDefault().id,
                            ),
                        )
                    }.onSuccess { message = app.getString(R.string.calendar_export_submitted) }
                        .onFailure { message = app.getString(R.string.calendar_export_submit_failed) }
                }
            }, enabled = range != "custom" || !LocalDate.parse(end).isBefore(LocalDate.parse(start))) {
                Text(stringResource(R.string.calendar_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_close)) } },
    )
}

@Composable
private fun ExternalCalendarsDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(emptyList<ExternalCalendarAccountDto>()) }
    var selecting by remember { mutableStateOf<ExternalCalendarAccountDto?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { accounts = runCatching { api.listExternalCalendarAccounts() }.getOrDefault(emptyList()) }
    FullCalendarDialog(stringResource(R.string.calendar_external_title), onDismiss) {
        Text(stringResource(R.string.calendar_external_supported))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            listOf("google" to "Google", "microsoft" to "Microsoft").forEach { (provider, label) ->
                Button(onClick = {
                    scope.launch {
                        runCatching { api.authorizeExternalCalendar(ExternalAuthorizeRequest(provider)) }
                            .onSuccess { CustomTabsIntent.Builder().build().launchUrl(context, android.net.Uri.parse(it.authorizationUrl)) }
                            .onFailure { error = true }
                    }
                }) { Text(stringResource(R.string.calendar_external_add_provider, label)) }
            }
        }
        accounts.forEach { account ->
            Text("${account.email} · ${account.status}", fontWeight = FontWeight.SemiBold)
            account.bindings.forEach { Text("${it.name} · ${it.syncStatus}${it.errorCode.takeIf(String::isNotBlank)?.let { code -> " ($code)" }.orEmpty()}") }
            Row {
                TextButton(onClick = { selecting = account }) { Text(stringResource(R.string.calendar_external_select)) }
                TextButton(onClick = { scope.launch { runCatching { api.syncExternalCalendar(account.id) }.onSuccess { reload++ } } }) { Text(stringResource(R.string.calendar_external_sync)) }
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.disconnectExternalCalendar(account.id) }
                            .onSuccess { reload++; onChanged() }.onFailure { error = true }
                    }
                }) { Text(stringResource(R.string.calendar_external_disconnect)) }
            }
        }
        if (error) Text(stringResource(R.string.calendar_external_operation_failed), color = MaterialTheme.colorScheme.error)
    }
    selecting?.let { account ->
        ProviderCalendarSelectionDialog(
            account = account,
            onDismiss = { selecting = null },
            onSaved = { selecting = null; reload++; onChanged() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDateField(label: String, value: String, onPick: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label：$value")
    }
    if (show) {
        val initial = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    show = false
                }) { Text(stringResource(R.string.calendar_confirm)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.calendar_cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun ProviderCalendarSelectionDialog(
    account: ExternalCalendarAccountDto,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val api = (LocalContext.current.applicationContext as WeMeetApp).apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var calendars by remember(account.id) { mutableStateOf<List<ProviderCalendarDto>>(emptyList()) }
    var selected by remember(account.id) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(account.id) { mutableStateOf(true) }
    var error by remember(account.id) { mutableStateOf(false) }
    LaunchedEffect(account.id) {
        runCatching { api.listProviderCalendars(account.id) }
            .onSuccess { result ->
                calendars = result
                val hasSavedSelection = result.any(ProviderCalendarDto::selected)
                selected = result.filter { it.selected || (!hasSavedSelection && it.primary) }
                    .mapTo(mutableSetOf()) { it.id }
            }
            .onFailure { error = true }
        loading = false
    }
    FullCalendarDialog(stringResource(R.string.calendar_external_choose_sync), onDismiss) {
        when {
            loading -> CircularProgressIndicator()
            error -> Text(stringResource(R.string.calendar_external_load_failed), color = MaterialTheme.colorScheme.error)
            else -> calendars.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = item.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + item.id else selected - item.id
                        },
                    )
                    Text(item.name + if (item.primary) stringResource(R.string.calendar_primary_suffix) else "")
                }
            }
        }
        Button(
            enabled = !loading && !error && selected.isNotEmpty(),
            onClick = {
                scope.launch {
                    runCatching {
                        api.selectProviderCalendars(
                            account.id,
                            SelectProviderCalendarsRequest(selected.toList()),
                        )
                    }.onSuccess { onSaved() }.onFailure { error = true }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.calendar_external_save_sync)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessSelector(value: String, values: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        "none" to stringResource(R.string.calendar_access_private),
        "free_busy" to stringResource(R.string.calendar_access_free_busy),
        "details" to stringResource(R.string.calendar_access_reader),
        "writer" to stringResource(R.string.calendar_access_writer),
        "admin" to stringResource(R.string.calendar_access_admin),
        "today" to stringResource(R.string.calendar_range_today),
        "week" to stringResource(R.string.calendar_range_week),
        "month" to stringResource(R.string.calendar_range_month),
        "custom" to stringResource(R.string.calendar_range_custom),
    )
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)) {
            Text(labels[value] ?: value)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded)
        }
        ExposedDropdownMenu(expanded, { expanded = false }, matchTextFieldWidth = false) {
            values.forEach { item ->
                DropdownMenuItem(text = { Text(labels[item] ?: item) }, onClick = { onSelect(item); expanded = false })
            }
        }
    }
}

@Composable
private fun FullCalendarDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_close)) }
                }
                content()
                Spacer(Modifier.height(Dimens.SpaceL))
            }
        }
    }
}

private fun generateQr(value: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 440, 440)
    Bitmap.createBitmap(440, 440, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until 440) for (y in 0 until 440) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}.getOrNull()

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("calendar", text))
}
