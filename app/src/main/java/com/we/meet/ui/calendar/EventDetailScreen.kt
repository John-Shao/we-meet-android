package com.we.meet.ui.calendar

import android.app.Application
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.RsvpRequest
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TransferEventRequest
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.ui.chat.ForwardCreateGroupFlow
import com.we.meet.feature.im.ui.chat.ForwardPicker
// 复用会议详情页的会议号分组格式,避免两处实现漂移。
import com.we.meet.ui.home.formatSlugDigits
import com.we.meet.ui.meetingroom.meetingRoomScheduleTitle
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.OffsetDateTime
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
    /** Strictly the current organizer; shared-calendar writers cannot transfer. */
    val canTransfer: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: Boolean = false,
    val transferring: Boolean = false,
    val transferError: Boolean = false,
    /**
     * 会后纪要(阶段 2:日程详情覆盖「会前预约 → 会后纪要」全生命周期)。
     * 仅「已结束 + 有房间」时才拉,未来日程不平白打一次 404;拿不到就是 null,
     * 界面不显示纪要区块,不制造空入口。
     */
    val summary: SummaryDto? = null,
)

class EventDetailViewModel(
    app: Application,
    private val eventId: String,
) : AndroidViewModel(app) {

    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val userApi = (app as WeMeetApp).apiClient.userApi
    private val roomApi = (app as WeMeetApp).apiClient.roomApi

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
                            canManage = e.canEdit || e.canDelete,
                            canTransfer = selfUserId != null && e.organizer?.id == selfUserId,
                        )
                    }
                    loadSummaryIfEnded(e)
                }
                .onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    /**
     * 会后纪要:仅「已结束 + 有房间」才拉。404(尚未生成/从未开会)是常态,
     * 静默留 null —— 界面据此不显示纪要区块,不给用户一个点进去空空如也的入口。
     */
    private fun loadSummaryIfEnded(event: CalendarEventDto) {
        val roomId = event.room ?: return
        val ended = runCatching {
            OffsetDateTime.parse(event.endAt).toInstant().isBefore(Instant.now())
        }.getOrDefault(false)
        if (!ended) return
        viewModelScope.launch {
            runCatching { roomApi.getSummary(roomId) }
                .onSuccess { s -> _ui.update { it.copy(summary = s) } }
        }
    }

    /**
     * Delete the event (organizer only); [onDone] fires on success so the screen
     * pops. Repeating events use an explicit one/following/all [scope].
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

    fun transfer(newOrganizerId: String, keepOriginalOrganizer: Boolean, onDone: () -> Unit) {
        if (_ui.value.transferring || !_ui.value.canTransfer) return
        _ui.update { it.copy(transferring = true, transferError = false) }
        viewModelScope.launch {
            runCatching {
                api.transferEvent(
                    eventId,
                    TransferEventRequest(newOrganizerId, keepOriginalOrganizer),
                )
            }
                .onSuccess { onDone() }
                .onFailure {
                    _ui.update { state ->
                        state.copy(transferring = false, transferError = true)
                    }
                }
        }
    }

    fun consumeTransferError() {
        _ui.update { it.copy(transferError = false) }
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
    /** 会后纪要入口 → 会议详情页(完整纪要/待办/转录在那里渲染)。 */
    onOpenSummary: (roomId: String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val vm: EventDetailViewModel = viewModel(
        key = "event-$eventId",
        factory = EventDetailViewModel.factory(app, eventId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 单次/主事件删除:原确认弹窗(主事件=删整个系列)。
    var confirmDelete by remember { mutableStateOf(false) }
    // 重复子场次的编辑/删除都先选择 one/following/all。主事件走直连
    // (编辑=全部,删除=系列确认)。
    var editScopeAsk by remember { mutableStateOf(false) }
    var deleteScopeAsk by remember { mutableStateOf(false) }
    // 分享日程到聊天(对标飞书:顶栏分享,删除收进「更多」)。
    var showShare by remember { mutableStateOf(false) }
    var showShareGroup by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showTransferPicker by remember { mutableStateOf(false) }
    var transferCandidate by remember { mutableStateOf<PickedMember?>(null) }
    var keepOriginalOrganizer by remember { mutableStateOf(true) }
    val imSession = remember { ImSession.get(app) }

    // Re-fetch on resume so an edit made on the edit screen shows on return.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val deleteFailedMsg = stringResource(R.string.event_delete_failed)
    val transferFailedMsg = stringResource(R.string.event_transfer_failed)
    LaunchedEffect(ui.deleteError) {
        if (ui.deleteError) {
            snackbarHostState.showSnackbar(deleteFailedMsg)
            vm.consumeDeleteError()
        }
    }
    LaunchedEffect(ui.transferError) {
        if (ui.transferError) {
            snackbarHostState.showSnackbar(transferFailedMsg)
            vm.consumeTransferError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.event_detail_title),
                onBack = onBack,
                actions = {
                    // 普通日程不限组织者；私密日程不提供转发入口。
                    if (ui.event != null && ui.event?.visibility != "private") {
                        IconButton(onClick = { showShare = true }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.event_action_share),
                            )
                        }
                    }
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
                        // 删除收进「更多」(对标飞书),避免高危操作与常用操作并排。
                        Box {
                            IconButton(
                                enabled = !ui.deleting && !ui.transferring,
                                onClick = { showMore = true },
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.event_action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = showMore,
                                onDismissRequest = { showMore = false },
                            ) {
                                if (ui.canTransfer) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.event_action_transfer)) },
                                        onClick = {
                                            showMore = false
                                            showTransferPicker = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.event_action_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMore = false
                                        if (isOccurrence) deleteScopeAsk = true else confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        // 分享日程到聊天:发 content_type='event-card'(协议 v1,与 Web
        // buildEventCardBody / AppNav 创建回发卡片一致),收端渲染成可点卡片。
        ui.event?.let { ev ->
            if (showShare) {
                val cardBody = org.json.JSONObject().apply {
                    put("v", 1)
                    put("kind", "created")
                    put("event_id", ev.id)
                    put("title", ev.title)
                    put("start", ev.startAt)
                    put("end", ev.endAt)
                    put("all_day", ev.allDay)
                    if (ev.allDay && ev.startDate != null && ev.endDate != null) {
                        put("start_date", ev.startDate)
                        put("end_date", ev.endDate)
                    }
                    put("attendee_count", ev.attendees.size)
                    put("organizer_name", ev.organizer?.fullName ?: "")
                }.toString()
                ForwardPicker(
                    deps = app,
                    targets = imSession.allForwardTargets(),
                    onForward = { cids ->
                        cids.forEach { imSession.sendMessageAsync(it, cardBody, "event-card") }
                        showShare = false
                    },
                    onCreateGroupForward = { showShareGroup = true },
                    onDismiss = { showShare = false },
                )
                if (showShareGroup) {
                    ForwardCreateGroupFlow(
                        deps = app,
                        onCreated = { newCid ->
                            imSession.sendMessageAsync(newCid, cardBody, "event-card")
                            showShareGroup = false
                            showShare = false
                        },
                        onCancel = { showShareGroup = false },
                    )
                }
            }
        }
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
        if (showTransferPicker) {
            ContactPicker(
                deps = app,
                mode = ContactPickerMode.Single,
                includeExternal = false,
                excludeUserIds = setOfNotNull(ui.event?.organizer?.id),
                onConfirm = { picked ->
                    transferCandidate = picked.firstOrNull()
                    keepOriginalOrganizer = true
                    showTransferPicker = false
                },
                onDismiss = { showTransferPicker = false },
            )
        }
        transferCandidate?.let { candidate ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { transferCandidate = null },
                title = { Text(stringResource(R.string.event_transfer_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.event_transfer_target,
                                candidate.displayName,
                            ),
                        )
                        Spacer(Modifier.height(Dimens.SpaceS))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                keepOriginalOrganizer = !keepOriginalOrganizer
                            },
                        ) {
                            Checkbox(
                                checked = keepOriginalOrganizer,
                                onCheckedChange = { keepOriginalOrganizer = it },
                            )
                            Text(stringResource(R.string.event_transfer_keep_original))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !ui.transferring,
                        onClick = {
                            vm.transfer(
                                candidate.userId,
                                keepOriginalOrganizer,
                                onDone = onBack,
                            )
                        },
                    ) { Text(stringResource(R.string.common_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { transferCandidate = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
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
                options = DELETE_EVENT_SCOPES,
                danger = true,
                onConfirm = { scope ->
                    deleteScopeAsk = false
                    vm.delete(
                        scope = deleteScopeForApi(scope),
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
                    ui = ui,
                    onOpenSummary = onOpenSummary,
                )
            }
            // 删除进行中:半透明遮罩 + 转圈,拦截交互避免二次触发。
            if (ui.deleting || ui.transferring) {
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
    ui: EventDetailUiState,
    onOpenSummary: (roomId: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as com.we.meet.WeMeetApp
    val parsedFull = event.toParsed(app.settingsStore.calendarZoneId())
    val parsed = parsedFull?.ui
    val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    val dayFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    val displayTitle = if (event.detailsRedacted) {
        stringResource(
            if (event.visibility == "private") R.string.calendar_private_event
            else R.string.calendar_busy,
        )
    } else {
        event.title
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.SpaceXl),
    ) {
        Spacer(Modifier.height(Dimens.IconTiny))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayTitle,
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
        Spacer(Modifier.height(Dimens.SpaceS))
        if (event.detailsRedacted) {
            Text(
                text = stringResource(
                    if (event.visibility == "private") R.string.event_private_redacted
                    else R.string.event_busy_redacted,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.SpaceS),
            )
        }
        if (parsed != null) {
            Text(
                text = if (parsed.allDay) {
                    // All-day dates render in the event's authored zone (device-TZ
                    // formatting would shift the shown day ±1 vs the calendar grid).
                    val allDayDate = parsed.startDate ?: parsed.start.toLocalDate()
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
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
        }
        // 只显示真正会响的那一条:后端按 max(reminders) 算触发点并只推一次,
        // 把历史多值数据全列出来等于承诺了不会发生的提醒。
        val effectiveReminder = event.reminders.maxOrNull()
        if (effectiveReminder != null) {
            val res = LocalContext.current.resources
            Text(
                text = "🔔 ${stringResource(R.string.calendar_field_reminder)}: " +
                    reminderLabel(res, effectiveReminder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
        }

        // P9 实体会议室 —— 属于「地点」这类信息,和时间/组织者/提醒放在一起,
        // 不并进下面的入会区块(那是 LiveKit 视频房间)。取消的日程照常展示:
        // 用户仍需要知道原本订的是哪一间。
        event.meetingRoom?.let { room ->
            val detail = buildString {
                append(
                    meetingRoomScheduleTitle(room.node?.name, room.code, room.name),
                )
                append(" · ")
                append(
                    stringResource(R.string.meeting_room_capacity_people, room.capacity),
                )
            }
            Text(
                text = "🏢 ${stringResource(R.string.meeting_room_detail_label)}: $detail",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.SpaceXs),
            )
            if (room.bookingStatus == "conflict") {
                Text(
                    text = stringResource(R.string.meeting_room_booking_conflict),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (parsed?.roomSlug != null && parsed.cancelled.not()) {
            // 会议信息(对标飞书:日程详情内嵌会议区块)—— 会议号/链接是「把会
            // 发给别人」的高频动作,原先只有一个入会按钮拿不到。
            Spacer(Modifier.height(Dimens.IconTiny))
            MeetingInfoBlock(slug = parsed.roomSlug)
            // 会后纪要:紧跟会议信息,构成「会前(会议号/链接)→ 会后(纪要)」。
            // 这里只给摘要 + 入口,完整纪要/待办/转录仍由会议详情页渲染。
            val summary = ui.summary
            if (summary != null && summary.status != "failed" && event.room != null) {
                Spacer(Modifier.height(Dimens.SpaceM))
                SummaryEntryBlock(
                    summary = summary,
                    onOpen = { onOpenSummary(event.room) },
                )
            }
            Spacer(Modifier.height(Dimens.IconTiny))
            Button(
                onClick = { onJoinSlug(parsed.roomSlug) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Spacer(Modifier.padding(start = Dimens.SpaceS))
                Text(stringResource(R.string.event_join_meeting))
            }
        }

        // RSVP — only rendered when the backend gave the caller an attendee row
        // (`my_rsvp` non-null); the organizer's attendance is implied.
        if (event.myRsvp != null && !ui.canManage) {
            Spacer(Modifier.height(Dimens.IconSmall))
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
                    modifier = Modifier.padding(top = Dimens.SpaceXs),
                )
            }
        }

        if (event.description.isNotBlank()) {
            Spacer(Modifier.height(Dimens.IconSmall))
            HorizontalDivider()
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = Dimens.SpaceM),
            )
        }

        Spacer(Modifier.height(Dimens.SpaceM))
        HorizontalDivider()
        Text(
            text = stringResource(R.string.event_attendees_count, event.attendees.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = Dimens.SpaceS),
        )
        event.attendees.forEach { attendee ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceXs),
            ) {
                val (icon, tint) = when (attendee.rsvp) {
                    "accepted" -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
                    "declined" -> Icons.Filled.Close to MaterialTheme.colorScheme.error
                    else -> Icons.Filled.QuestionMark to MaterialTheme.colorScheme.outline
                }
                Icon(icon, contentDescription = attendee.rsvp, tint = tint, modifier = Modifier.size(Dimens.IconTiny))
                Text(
                    text = attendee.fullName?.takeIf { it.isNotBlank() } ?: attendee.email.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = Dimens.SpaceS),
                )
                if (attendee.role == "organizer") {
                    Text(
                        text = stringResource(R.string.event_organizer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = Dimens.SpaceS),
                    )
                }
                if (attendee.role == "optional") {
                    Text(
                        text = stringResource(R.string.event_attendee_optional),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.SpaceS),
                    )
                }
            }
        }
        Spacer(Modifier.height(Dimens.SpaceXxl))
    }
}

/**
 * 会议信息区块(对标飞书:日程详情内嵌会议信息)——会议号 + 链接,各带复制。
 *
 * 复用会议详情页的 [formatSlugDigits] 与 detail_copy 文案,保证两处会议号
 * 分组格式一致。电话拨入需要 pin_code,日程接口目前不返回(且详情刚放宽为
 * 「凭 id 只读」,暴露 PIN 要单独评估),故本区块暂不含拨入。
 */
@Composable
private fun MeetingInfoBlock(slug: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.detail_copied)
    val link = BuildConfig.WE_MEET_BASE_URL.trimEnd('/') + "/" + slug
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(Dimens.CornerM),
            )
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.event_meeting_no),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Dimens.SpaceS))
            Text(
                text = formatSlugDigits(slug),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copy(slug) }) {
                Text(stringResource(R.string.detail_copy))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.event_meeting_link),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Dimens.SpaceS))
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copy(link) }) {
                Text(stringResource(R.string.detail_copy))
            }
        }
    }
}

/**
 * 会后纪要入口:摘要两行预览 + 「查看纪要」。完整纪要/待办/转录仍在会议详情页,
 * 这里不重复实现,也不把重内容堆进日程详情。
 */
@Composable
private fun SummaryEntryBlock(summary: SummaryDto, onOpen: () -> Unit) {
    // 展示优先用人工编辑版,回落 AI 原文;去掉 markdown 记号只留可读预览。
    val preview = (summary.effective_content?.takeIf { it.isNotBlank() }
        ?: summary.content)
        .replace(Regex("[#*`>\\-\\n]+"), " ")
        .trim()
        .take(90)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(Dimens.CornerM),
            )
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconTiny),
            )
            Spacer(Modifier.width(Dimens.SpaceXs))
            Text(
                text = stringResource(R.string.event_summary),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (summary.status == "pending") {
                Spacer(Modifier.width(Dimens.SpaceXs))
                Text(
                    text = stringResource(R.string.event_summary_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (preview.isNotBlank()) {
            Text(
                text = "$preview…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dimens.SpaceXxs),
            )
        }
        Text(
            text = stringResource(R.string.event_summary_view),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Dimens.SpaceXs),
        )
    }
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
                            .padding(vertical = Dimens.SpaceXs),
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { selected = option },
                        )
                        Text(
                            text = scopeLabel(option),
                            modifier = Modifier.padding(start = Dimens.SpaceXs),
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

internal val DELETE_EVENT_SCOPES = listOf("one", "following", "all")

internal fun deleteScopeForApi(scope: String): String {
    require(scope in DELETE_EVENT_SCOPES) { "Unsupported recurrence scope: $scope" }
    return scope
}
