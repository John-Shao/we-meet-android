package com.we.meet.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.data.api.dto.CreateEventRequest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** New-event form (route `create_event?epochDay=`). Pops back on success. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEventScreen(
    initialEpochDay: Long?,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()

    val initialDate = initialEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    // Default slot: next full hour (today) or 09:00 (another day), 1h long.
    val defaultStart = remember {
        if (initialDate == LocalDate.now()) {
            LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        } else {
            initialDate.atTime(9, 0)
        }
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart.plusHours(1)) }
    var reminderMinutes by remember { mutableStateOf<Int?>(10) }
    var attendees by remember { mutableStateOf<List<PickedMember>>(emptyList()) }

    var showPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    fun submit() {
        if (title.isBlank() || submitting) return
        val zone = ZoneId.systemDefault()
        val (startInstant, endInstant) = if (allDay) {
            // Web convention: local midnight → exclusive next-midnight.
            start.toLocalDate().atStartOfDay(zone).toInstant() to
                end.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        } else {
            start.atZone(zone).toInstant() to end.atZone(zone).toInstant()
        }
        if (!endInstant.isAfter(startInstant)) {
            errorRes = R.string.calendar_error_end_before_start
            return
        }
        submitting = true
        errorRes = null
        scope.launch {
            runCatching {
                app.apiClient.calendarApi.createEvent(
                    CreateEventRequest(
                        title = title.trim(),
                        startAt = isoUtc(startInstant),
                        endAt = isoUtc(endInstant),
                        allDay = allDay,
                        reminders = reminderMinutes?.let { listOf(it) } ?: emptyList(),
                        attendeeIds = attendees.map { it.userId },
                        description = description.trim(),
                        timezone = zone.id,
                    )
                )
            }
                .onSuccess { onClose() }
                .onFailure {
                    submitting = false
                    errorRes = R.string.calendar_create_failed
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { submit() }, enabled = title.isNotBlank() && !submitting) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).height(18.dp),
                            )
                        } else {
                            Text(stringResource(R.string.calendar_action_create))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                placeholder = { Text(stringResource(R.string.calendar_field_title_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(stringResource(R.string.calendar_field_all_day))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }
            HorizontalDivider()

            DateTimeRow(
                label = stringResource(R.string.calendar_field_start),
                value = start,
                allDay = allDay,
                onChange = { newStart ->
                    // Editing start shifts end to keep the duration.
                    val duration = Duration.between(start, end)
                    start = newStart
                    end = newStart.plus(duration)
                },
            )
            DateTimeRow(
                label = stringResource(R.string.calendar_field_end),
                value = end,
                allDay = allDay,
                onChange = { end = it },
            )
            HorizontalDivider()

            ReminderDropdown(
                selectedMinutes = reminderMinutes,
                onSelect = { reminderMinutes = it },
            )
            HorizontalDivider()

            Text(
                text = stringResource(R.string.calendar_field_attendees),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    attendees.forEach { picked ->
                        InputChip(
                            selected = true,
                            onClick = { attendees = attendees - picked },
                            label = { Text(picked.displayName) },
                        )
                    }
                }
                TextButton(onClick = { showPicker = true }) {
                    Text(stringResource(R.string.calendar_add_attendees))
                }
            }
            HorizontalDivider()

            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 500) description = it },
                placeholder = { Text(stringResource(R.string.calendar_field_description_hint)) },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )

            errorRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPicker) {
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            excludeUserIds = attendees.map { it.userId }.toSet(),
            onConfirm = { picked ->
                attendees = (attendees + picked).distinctBy { it.userId }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    label: String,
    value: LocalDateTime,
    allDay: Boolean,
    onChange: (LocalDateTime) -> Unit,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(label)
        Row {
            Text(
                text = value.toLocalDate().format(dateFmt),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showDate = true },
            )
            if (!allDay) {
                Spacer(Modifier.padding(start = 12.dp))
                Text(
                    text = value.toLocalTime().format(timeFmt),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { showTime = true },
                )
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(LocalDateTime.of(date, value.toLocalTime()))
                    }
                    showDate = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTime) {
        // M3 ships no TimePickerDialog — a TimePicker inside an AlertDialog.
        val state = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalDateTime.of(value.toLocalDate(), LocalTime.of(state.hour, state.minute)))
                    showTime = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDropdown(selectedMinutes: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<Int?> = listOf(null, 0, 5, 10, 15, 30, 60, 1440)

    @Composable
    fun labelFor(minutes: Int?): String = when (minutes) {
        null -> stringResource(R.string.calendar_reminder_none)
        0 -> stringResource(R.string.calendar_reminder_at_time)
        60 -> stringResource(R.string.calendar_reminder_hour)
        1440 -> stringResource(R.string.calendar_reminder_day)
        else -> stringResource(R.string.calendar_reminder_minutes, minutes)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(stringResource(R.string.calendar_field_reminder))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(),
            ) {
                Text(labelFor(selectedMinutes))
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(labelFor(minutes)) },
                        onClick = {
                            onSelect(minutes)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun isoUtc(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant)
