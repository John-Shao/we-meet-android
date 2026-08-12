package com.we.meet.ui.calendar

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.data.api.dto.CalendarAccessGrantDto
import com.we.meet.data.api.dto.CalendarSubscriptionDto
import com.we.meet.data.api.dto.PersonalCalendarDto
import com.we.meet.data.api.dto.SaveCalendarGrantRequest
import com.we.meet.data.api.dto.SubscribeCalendarRequest
import com.we.meet.data.api.dto.UpdateCalendarGrantRequest
import com.we.meet.data.api.dto.UpdatePersonalCalendarRequest
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

private enum class CalendarPickerPurpose { SHARE, SUBSCRIBE }

/** Personal-calendar default permission, explicit grants, and subscriptions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSharingDialog(onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val api = app.apiClient.calendarApi
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf<PersonalCalendarDto?>(null) }
    var grants by remember { mutableStateOf<List<CalendarAccessGrantDto>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<CalendarSubscriptionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var pickerPurpose by remember { mutableStateOf<CalendarPickerPurpose?>(null) }

    LaunchedEffect(reload) {
        loading = true
        error = false
        runCatching {
            Triple(
                api.getMyCalendar(),
                api.listCalendarGrants(),
                api.listCalendarSubscriptions(),
            )
        }.onSuccess { result ->
            calendar = result.first
            grants = result.second
            subscriptions = result.third
        }.onFailure { error = true }
        loading = false
    }

    fun mutate(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        error = false
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    onChanged()
                    reload++
                }
                .onFailure { error = true }
            saving = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.CornerL),
            tonalElevation = Dimens.ElevationSticky,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Dimens.SpaceL, end = Dimens.SpaceS),
                ) {
                    Text(
                        stringResource(R.string.calendar_sharing_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                    }
                }
                HorizontalDivider()

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    calendar == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.calendar_sharing_load_failed))
                            Button(onClick = { reload++ }, modifier = Modifier.padding(top = Dimens.SpaceM)) {
                                Text(stringResource(R.string.calendar_retry))
                            }
                        }
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Dimens.SpaceL,
                            vertical = Dimens.SpaceM,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                    ) {
                        item {
                            Text(
                                stringResource(R.string.calendar_sharing_org_default),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.calendar_sharing_org_default_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AccessDropdown(
                                value = calendar!!.organizationDefaultAccess,
                                includeNone = true,
                                enabled = !saving,
                                onSelect = { value ->
                                    mutate {
                                        calendar = api.updatePersonalCalendar(
                                            calendar!!.id,
                                            UpdatePersonalCalendarRequest(value),
                                        )
                                    }
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = Dimens.SpaceS))
                        }

                        item {
                            SectionHeader(
                                title = stringResource(R.string.calendar_sharing_grants),
                                action = stringResource(R.string.calendar_sharing_add_grant),
                                enabled = !saving,
                                onClick = { pickerPurpose = CalendarPickerPurpose.SHARE },
                            )
                        }
                        if (grants.isEmpty()) {
                            item { EmptyHint(R.string.calendar_sharing_no_grants) }
                        } else {
                            items(grants, key = { it.id }) { grant ->
                                SharingRow(
                                    title = grant.grantee.fullName
                                        ?: grant.grantee.shortName
                                        ?: grant.grantee.id.take(8),
                                    subtitle = grant.grantee.organization?.name,
                                    permission = grant.permission,
                                    enabled = !saving,
                                    onPermission = { value ->
                                        mutate {
                                            api.updateCalendarGrant(
                                                grant.id,
                                                UpdateCalendarGrantRequest(value),
                                            )
                                        }
                                    },
                                    onDelete = { mutate { api.deleteCalendarGrant(grant.id) } },
                                )
                            }
                        }

                        item { HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.SpaceS)) }
                        item {
                            SectionHeader(
                                title = stringResource(R.string.calendar_sharing_subscriptions),
                                action = stringResource(R.string.calendar_sharing_add_subscription),
                                enabled = !saving,
                                onClick = { pickerPurpose = CalendarPickerPurpose.SUBSCRIBE },
                            )
                        }
                        if (subscriptions.isEmpty()) {
                            item { EmptyHint(R.string.calendar_sharing_no_subscriptions) }
                        } else {
                            items(subscriptions, key = { it.id }) { subscription ->
                                SharingRow(
                                    title = subscription.owner.fullName
                                        ?: subscription.owner.shortName
                                        ?: subscription.owner.id.take(8),
                                    subtitle = subscription.owner.organization?.name,
                                    permission = subscription.permission,
                                    enabled = false,
                                    onPermission = {},
                                    onDelete = {
                                        mutate { api.unsubscribeCalendar(subscription.id) }
                                    },
                                )
                            }
                        }
                        if (error) {
                            item {
                                Text(
                                    stringResource(R.string.calendar_sharing_save_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(Dimens.SpaceL)) }
                    }
                }
            }
        }
    }

    pickerPurpose?.let { purpose ->
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Single,
            includeExternal = true,
            excludeUserIds = when (purpose) {
                CalendarPickerPurpose.SHARE -> grants.map { it.grantee.id }.toSet()
                CalendarPickerPurpose.SUBSCRIBE -> subscriptions.map { it.owner.id }.toSet()
            },
            onConfirm = { picked ->
                val person = picked.firstOrNull() ?: return@ContactPicker
                pickerPurpose = null
                mutate {
                    when (purpose) {
                        CalendarPickerPurpose.SHARE -> api.saveCalendarGrant(
                            SaveCalendarGrantRequest(person.userId, "free_busy"),
                        )
                        CalendarPickerPurpose.SUBSCRIBE -> api.subscribeCalendar(
                            SubscribeCalendarRequest(person.userId),
                        )
                    }
                }
            },
            onDismiss = { pickerPurpose = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(action)
        }
    }
}

@Composable
private fun EmptyHint(textRes: Int) {
    Text(
        stringResource(textRes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharingRow(
    title: String,
    subtitle: String?,
    permission: String,
    enabled: Boolean,
    onPermission: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AccessDropdown(
            value = permission,
            includeNone = false,
            enabled = enabled,
            onSelect = onPermission,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.action_remove))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessDropdown(
    value: String,
    includeNone: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val values = if (includeNone) listOf("none", "free_busy", "details")
    else listOf("free_busy", "details")
    val label: @Composable (String) -> String = { permission ->
        stringResource(
            when (permission) {
                "details" -> R.string.calendar_sharing_details
                "none" -> R.string.calendar_sharing_none
                else -> R.string.calendar_sharing_free_busy
            },
        )
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        TextButton(
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        ) {
            Text(label(value), softWrap = false)
            if (enabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            matchTextFieldWidth = false,
        ) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option), softWrap = false) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
