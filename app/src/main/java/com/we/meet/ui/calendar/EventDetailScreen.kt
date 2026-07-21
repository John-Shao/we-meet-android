package com.we.meet.ui.calendar

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.RsvpRequest
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventDetailUiState(
    val event: CalendarEventDto? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val rsvpError: Boolean = false,
    /** Caller is the organizer → may edit/delete. */
    val canManage: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: Boolean = false,
)

class EventDetailViewModel(
    app: Application,
    private val eventId: String,
) : AndroidViewModel(app) {

    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val userApi = (app as WeMeetApp).apiClient.userApi

    private val _ui = MutableStateFlow(EventDetailUiState())
    val ui: StateFlow<EventDetailUiState> = _ui.asStateFlow()

    private var selfUserId: String? = null

    init {
        viewModelScope.launch {
            selfUserId = runCatching { userApi.getMe().id }.getOrNull()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.getEvent(eventId) }
                .onSuccess { e ->
                    _ui.update {
                        it.copy(
                            event = e,
                            loading = false,
                            error = false,
                            canManage = selfUserId != null && e.organizer?.id == selfUserId,
                        )
                    }
                }
                .onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    /**
     * Delete the event (organizer only); [onDone] fires on success so the screen
     * pops. [scope] = "following"(仅重复子场次)截断该场次及之后;null = 缺省
     * (子场次=仅此次记 exdate;主事件=删整个系列)。
     */
    fun delete(scope: String? = null, onDone: () -> Unit) {
        if (_ui.value.deleting) return
        _ui.update { it.copy(deleting = true, deleteError = false) }
        viewModelScope.launch {
            runCatching { api.deleteEvent(eventId, scope) }
                .onSuccess { onDone() }
                .onFailure { _ui.update { it.copy(deleting = false, deleteError = true) } }
        }
    }

    fun consumeDeleteError() {
        _ui.update { it.copy(deleteError = false) }
    }

    /** Optimistic RSVP; revert + flag on failure. */
    fun rsvp(status: String) {
        val before = _ui.value.event ?: return
        if (before.myRsvp == status) return
        _ui.update { it.copy(event = before.copy(myRsvp = status), rsvpError = false) }
        viewModelScope.launch {
            runCatching { api.rsvp(eventId, RsvpRequest(status)) }
                .onSuccess { refresh() }
                .onFailure { _ui.update { it.copy(event = before, rsvpError = true) } }
        }
    }

    companion object {
        fun factory(app: Application, eventId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { EventDetailViewModel(app, eventId) }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
    /** [scope] = one/following/all(重复子场次)或 null(单次/主事件)。 */
    onEdit: (eventId: String, scope: String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: EventDetailViewModel = viewModel(
        key = "event-$eventId",
        factory = EventDetailViewModel.factory(app, eventId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 单次/主事件删除:原确认弹窗(主事件=删整个系列)。
    var confirmDelete by remember { mutableStateOf(false) }
    // P2-M2 重复子场次:编辑/删除先弹三选范围(编辑=仅此次/此次及以后/全部;
    // 删除=仅此次/此次及以后)。主事件走直连(编辑=全部,删除=系列确认)。
    var editScopeAsk by remember { mutableStateOf(false) }
    var deleteScopeAsk by remember { mutableStateOf(false) }

    // Re-fetch on resume so an edit made on the edit screen shows on return.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val deleteFailedMsg = stringResource(R.string.event_delete_failed)
    LaunchedEffect(ui.deleteError) {
        if (ui.deleteError) {
            snackbarHostState.showSnackbar(deleteFailedMsg)
            vm.consumeDeleteError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (ui.canManage) {
                        // 重复「子场次」(recurrence_parent 非空)先弹三选;主事件/
                        // 单次直连(主事件编辑=后端全部,删除=系列确认)。
                        val isOccurrence = ui.event?.recurrenceParent != null
                        IconButton(
                            enabled = !ui.deleting,
                            onClick = {
                                if (isOccurrence) editScopeAsk = true else onEdit(eventId, null)
                            },
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.event_action_edit),
                            )
                        }
                        IconButton(
                            enabled = !ui.deleting,
                            onClick = {
                                if (isOccurrence) deleteScopeAsk = true else confirmDelete = true
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.event_action_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (confirmDelete) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = false },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmDelete = false
                        vm.delete(scope = null, onDone = onBack)
                    }) { Text(stringResource(R.string.event_action_delete)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                // 主重复系列(recurrence 非空且非子场次)删除会清掉整个系列,
                // 用专门文案警示;普通单次用通用确认。
                text = {
                    val isSeries = ui.event?.recurrence?.isNotBlank() == true
                    Text(
                        stringResource(
                            if (isSeries) R.string.event_delete_series_confirm
                            else R.string.event_delete_confirm,
                        ),
                    )
                },
            )
        }
        if (editScopeAsk) {
            EventScopeDialog(
                title = stringResource(R.string.event_edit_scope_title),
                options = listOf("one", "following", "all"),
                danger = false,
                onConfirm = { scope ->
                    editScopeAsk = false
                    onEdit(eventId, scope)
                },
                onDismiss = { editScopeAsk = false },
            )
        }
        if (deleteScopeAsk) {
            EventScopeDialog(
                title = stringResource(R.string.event_delete_scope_title),
                options = listOf("one", "following"),
                danger = true,
                onConfirm = { scope ->
                    deleteScopeAsk = false
                    // one=后端缺省(仅此次记 exdate);following=截断该场次及之后。
                    vm.delete(
                        scope = if (scope == "following") "following" else null,
                        onDone = onBack,
                    )
                },
                onDismiss = { deleteScopeAsk = false },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                ui.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                ui.error || ui.event == null -> Text(
                    text = stringResource(R.string.event_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> EventBody(
                    event = ui.event!!,
                    rsvpError = ui.rsvpError,
                    onRsvp = { vm.rsvp(it) },
                    onJoinSlug = onJoinSlug,
                )
            }
            // 删除进行中:半透明遮罩 + 转圈,拦截交互避免二次触发。
            if (ui.deleting) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(enabled = false) {},
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventBody(
    event: CalendarEventDto,
    rsvpError: Boolean,
    onRsvp: (String) -> Unit,
    onJoinSlug: (String) -> Unit,
) {
    val parsedFull = event.toParsed()
    val parsed = parsedFull?.ui
    val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    val dayFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (parsed?.cancelled == true) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            if (parsed?.cancelled == true) {
                Text(
                    text = stringResource(R.string.event_cancelled_flag),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (parsed != null) {
            Text(
                text = if (parsed.allDay) {
                    // All-day dates render in the event's authored zone (device-TZ
                    // formatting would shift the shown day ±1 vs the calendar grid).
                    val eventZone = parsedFull?.zone ?: parsed.start.zone
                    val allDayDate = parsed.start.withZoneSameInstant(eventZone)
                    "${allDayDate.format(dayFmt)} · ${stringResource(R.string.calendar_all_day)}"
                } else {
                    "${parsed.start.format(dateFmt)} – ${parsed.end.format(dateFmt)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        event.organizer?.fullName?.takeIf { it.isNotBlank() }?.let { organizer ->
            Text(
                text = "${stringResource(R.string.event_organizer)}: $organizer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (event.reminders.isNotEmpty()) {
            val res = LocalContext.current.resources
            Text(
                text = "🔔 ${stringResource(R.string.calendar_field_reminder)}: " +
                    event.reminders.joinToString("、") { reminderLabel(res, it) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (parsed?.roomSlug != null && parsed.cancelled.not()) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onJoinSlug(parsed.roomSlug) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(R.string.event_join_meeting))
            }
        }

        // RSVP — only rendered when the backend gave the caller an attendee row
        // (`my_rsvp` non-null); the organizer's attendance is implied.
        if (event.myRsvp != null) {
            Spacer(Modifier.height(20.dp))
            val options = listOf(
                "accepted" to stringResource(R.string.event_rsvp_accept),
                "tentative" to stringResource(R.string.event_rsvp_tentative),
                "declined" to stringResource(R.string.event_rsvp_decline),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = event.myRsvp == value,
                        onClick = { onRsvp(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            if (rsvpError) {
                Text(
                    text = stringResource(R.string.event_rsvp_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (event.description.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.event_attendees_count, event.attendees.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        event.attendees.forEach { attendee ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                val (icon, tint) = when (attendee.rsvp) {
                    "accepted" -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
                    "declined" -> Icons.Filled.Close to MaterialTheme.colorScheme.error
                    else -> Icons.Filled.QuestionMark to MaterialTheme.colorScheme.outline
                }
                Icon(icon, contentDescription = attendee.rsvp, tint = tint, modifier = Modifier.size(16.dp))
                Text(
                    text = attendee.fullName?.takeIf { it.isNotBlank() } ?: attendee.email.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
                if (attendee.role == "organizer") {
                    Text(
                        text = stringResource(R.string.event_organizer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Reminder lead-time → label, mirroring CreateEventScreen's dropdown. Plain
 * function (not @Composable) so it works inside a joinToString lambda. */
private fun reminderLabel(res: android.content.res.Resources, minutes: Int): String =
    when (minutes) {
        0 -> res.getString(R.string.calendar_reminder_at_time)
        60 -> res.getString(R.string.calendar_reminder_hour)
        1440 -> res.getString(R.string.calendar_reminder_day)
        else -> res.getString(R.string.calendar_reminder_minutes, minutes)
    }

/**
 * P2-M2 重复日程三选范围弹窗(编辑/删除共用)。弹窗本身即确认步骤——确认后
 * 直接执行,不再二次确认(对齐 Web EditScopeDialog)。[options] 决定给几项:
 * 编辑=one/following/all,删除=one/following。
 */
@Composable
private fun EventScopeDialog(
    title: String,
    options: List<String>,
    danger: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(options.first()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = option }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { selected = option },
                        )
                        Text(
                            text = scopeLabel(option),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(selected) }) {
                Text(
                    text = stringResource(R.string.ok),
                    color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "one" -> stringResource(R.string.event_scope_one)
    "following" -> stringResource(R.string.event_scope_following)
    else -> stringResource(R.string.event_scope_all)
}
