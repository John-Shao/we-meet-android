package com.we.meet.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.ui.theme.WeMeetTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.WeMeetApp
import com.we.meet.R

@Composable
fun HomeScreen(
    onCreateMeeting: () -> Unit,
    onJoinMeeting: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
    onHistoryClick: (roomId: String) -> Unit,
    /** P8:预约会议行 → 预约详情页(进会/复制/删除收进详情)。 */
    onScheduledClick: (slug: String, name: String, scheduledAtIso: String) -> Unit,
    onShareScheduledMeeting: (slug: String, name: String, scheduledAtIso: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app))
    val history by homeViewModel.history.collectAsStateWithLifecycle()
    val scheduledMeetings by homeViewModel.scheduledMeetings.collectAsStateWithLifecycle()
    val laterCreated by homeViewModel.laterCreated.collectAsStateWithLifecycle()
    val laterCreating by homeViewModel.laterCreating.collectAsStateWithLifecycle()
    val laterError by homeViewModel.laterError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(laterError) {
        if (laterError) {
            android.widget.Toast.makeText(
                context, R.string.home_create_failed, android.widget.Toast.LENGTH_SHORT,
            ).show()
            homeViewModel.consumeLaterError()
        }
    }

    // Refresh the server-side rooms list whenever Home becomes visible
    // again — covers returning from a meeting, the room-end flow, or a
    // create-on-another-device case (the user opened the App expecting
    // to see a room their Web session just made).
    LifecycleResumeEffect(homeViewModel) {
        homeViewModel.refreshRemoteRooms()
        onPauseOrDispose { }
    }

    var showLaterNameDialog by remember { mutableStateOf(false) }

    // Header (top bar + action zone + band) stays pinned; only the
    // meeting lists below scroll when the user swipes up. Same Feishu /
    // WeChat-style "fixed action shelf + scrolling timeline" layout.
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: tab title on the left, meeting-settings gear on the right.
        // (Scan-QR lives in the 消息 header's "more" menu; profile/app settings
        // stay behind the 消息 avatar — only meeting-scoped settings are here.)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_meeting),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.meeting_settings_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Action zone — padded, same background as the page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActionCard(
                icon = Icons.Default.Bolt,
                label = stringResource(R.string.home_create_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onCreateMeeting,
                modifier = Modifier.weight(1f),
            )
            ActionCard(
                icon = Icons.Default.AddBox,
                label = stringResource(R.string.home_join_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onJoinMeeting,
                modifier = Modifier.weight(1f),
            )
            ActionCard(
                icon = Icons.Default.Schedule,
                label = stringResource(R.string.home_create_later_meeting),
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { showLaterNameDialog = true },
                modifier = Modifier.weight(1f),
            )
        }

        // Full-width tinted band separating the action zone from the
        // history list — mirrors the Feishu home layout.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(WeMeetTheme.extras.surfaceBand),
        )

        // Scheduled + History zones — padded inside one column. This is
        // the only scrollable region; the action shelf above stays put.
        // Scheduled list renders nothing when empty, so on a fresh
        // install the history section still sits flush with the band.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // P8(对标飞书):行点击进详情,操作(进会/复制/删除)收进详情页。
            ScheduledMeetingsList(
                rooms = scheduledMeetings,
                onEntryClick = { room ->
                    onScheduledClick(
                        room.slug.orEmpty(),
                        room.name.orEmpty(),
                        room.scheduled_at.orEmpty(),
                    )
                },
            )
            HistoryList(
                entries = history,
                // P8 实测修正:统一点击进详情页。此前按 closed_at 分流
                // (进行中→重进会议),但大量房间从未显式结束、closed_at
                // 恒空,同一列表头尾行为不一致;重进会议在详情页一键可达。
                onEntryClick = { entry -> onHistoryClick(entry.roomId) },
            )
        }
    }

    // Create-later runs after the name dialog closes; without this the screen
    // shows no feedback during the network round-trip (invited to re-tap).
    if (laterCreating) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            CircularProgressIndicator()
        }
    }

    if (showLaterNameDialog) {
        LaterMeetingDialog(
            initial = homeViewModel.defaultMeetingName,
            onConfirm = { name, iso ->
                showLaterNameDialog = false
                homeViewModel.createLaterMeeting(name, iso)
            },
            onDismiss = { showLaterNameDialog = false },
        )
    }

    laterCreated?.let { created ->
        com.we.meet.ui.room.InviteSheet(
            roomName = created.displayName,
            roomSlug = created.slug,
            scheduledAtIso = created.scheduledAtIso,
            onEnterRoom = {
                val slug = created.slug
                homeViewModel.dismissLaterCreated()
                onJoinSlug(slug)
            },
            onShareToChat = {
                onShareScheduledMeeting(created.slug, created.displayName, created.scheduledAtIso.orEmpty())
            },
            onDismiss = { homeViewModel.dismissLaterCreated() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaterMeetingDialog(
    initial: String,
    onConfirm: (name: String, scheduledAtIso: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(initial) { mutableStateOf(initial) }
    // Scheduled instant in epoch millis (system zone). Null = no time picked.
    var scheduledMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // Date selected in the first picker, awaiting time selection in the second.
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_create_later_meeting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(80) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.home_create_later_name_label)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // Scheduled time is required — when unset we show
                        // a "please pick" prompt rather than a neutral
                        // hint so users notice it's missing.
                        text = scheduledMillis?.let { formatMillisForDisplay(it) }
                            ?: stringResource(R.string.home_create_later_no_time),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scheduledMillis == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(stringResource(
                            if (scheduledMillis == null) R.string.home_create_later_pick_time
                            else R.string.home_create_later_edit_time
                        ))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val iso = scheduledMillis?.let { millisToIsoOffset(it) } ?: return@TextButton
                    onConfirm(input.trim(), iso)
                },
                // Scheduled time is required AND must not be in the past
                // (the user could otherwise pick today + an already-past
                // hour with the time picker, which the date picker can't
                // gate on its own).
                enabled = input.trim().isNotEmpty() &&
                    scheduledMillis != null &&
                    (scheduledMillis ?: 0L) > System.currentTimeMillis(),
            ) { Text(stringResource(R.string.home_create_later_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_create_later_cancel))
            }
        },
    )

    if (showDatePicker) {
        // Reject any calendar day before today (local-time midnight). The
        // year cutoff stays unconstrained so the user can scroll forward
        // freely.
        val todayStartUtc = remember {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = (scheduledMillis ?: System.currentTimeMillis())
                .coerceAtLeast(todayStartUtc),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayStartUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = datePickerState.selectedDateMillis
                    showDatePicker = false
                    if (picked != null) {
                        pendingDateMillis = picked
                        showTimePicker = true
                    }
                }) { Text(stringResource(R.string.home_create_later_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.home_create_later_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initHour = scheduledMillis?.let { hourOf(it) } ?: 9
        val initMinute = scheduledMillis?.let { minuteOf(it) } ?: 0
        val timePickerState = rememberTimePickerState(
            initialHour = initHour,
            initialMinute = initMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.home_create_later_pick_time)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val date = pendingDateMillis ?: System.currentTimeMillis()
                    scheduledMillis = combineDateAndTime(date, timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) { Text(stringResource(R.string.home_create_later_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.home_create_later_cancel))
                }
            },
        )
    }
}

// ── Date/time helpers ────────────────────────────────────────────────────
//
// minSdk 29 covers full java.time without desugaring. DatePicker hands us
// UTC midnight for the selected day, TimePicker hands us local hour+minute;
// the helpers below combine them in the system zone so the saved instant
// matches what the user picked in their wall-clock view, and serialise to
// ISO 8601 with offset for the backend's DateTimeField.

private fun combineDateAndTime(dateMillisUtcMidnight: Long, hour: Int, minute: Int): Long {
    val date = java.time.Instant.ofEpochMilli(dateMillisUtcMidnight)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
    val localDt = java.time.LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
    return localDt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun millisToIsoOffset(millis: Long): String {
    val odt = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toOffsetDateTime()
    return odt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

private fun formatMillisForDisplay(millis: Long): String {
    val local = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    return local.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

private fun hourOf(millis: Long): Int =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .hour

private fun minuteOf(millis: Long): Int =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .minute

@Composable
private fun ActionCard(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
