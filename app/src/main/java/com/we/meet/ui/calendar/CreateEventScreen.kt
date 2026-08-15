package com.we.meet.ui.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.R
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.data.api.dto.AttendeeEntryRequest
import com.we.meet.data.api.dto.CreateEventRequest
import com.we.meet.data.api.dto.MeetingRoomBriefDto
import com.we.meet.data.api.dto.UpdateEventRequest
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.ui.meetingroom.MeetingRoomPicker
import com.we.meet.ui.meetingroom.compactMeetingRoomPathLabel
import com.we.meet.ui.meetingroom.meetingRoomTitle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import retrofit2.HttpException

private val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val EVENT_VISIBILITIES = listOf("default", "public", "private")

/**
 * Event form (route `create_event?epochDay=&eventId=`). Create mode when
 * [editEventId] is null; otherwise loads the event, prefills the fields, and
 * PATCHes on save. P8:非重复日程的编辑态同样可增删参与者(attendee_ids
 * 全量同步,web parity);重复日程编辑隐藏参与者区(服务端三选路径剔除该
 * 字段)。Pops back on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEventScreen(
    initialEpochDay: Long?,
    onClose: () -> Unit,
    editEventId: String? = null,
    /** P2-M2 重复子场次编辑范围(one/following/all);单次/主事件为 null。 */
    editScope: String? = null,
    /** P8 忙闲页预填:精确起止时刻(epoch 秒,免时区串扰);优先于 epochDay。 */
    initialStartEpochSecond: Long? = null,
    initialEndEpochSecond: Long? = null,
    /** P8 忙闲页预填:参与者 we-meet uuid,屏内经目录补全身份(失败静默丢弃)。 */
    prefillAttendeeIds: List<String> = emptyList(),
    /** 会议室时间轴预填。仅创建模式读取，不覆盖编辑态已有会议室。 */
    initialMeetingRoomId: String? = null,
    /** P8:创建成功回调(仅创建模式,编辑不触发)——IM 链路用来回发日程卡片。 */
    onCreated: ((com.we.meet.data.api.dto.CalendarEventDto) -> Unit)? = null,
    /** P8:来源 IM 会话 cid,随创建落库(M3 变更推送用);非 IM 链路为 null。 */
    sourceConversationId: String? = null,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val workingHours by app.settingsStore.workingHours.collectAsStateWithLifecycle()
    val calendarTimezoneMode by app.settingsStore.calendarTimezoneMode.collectAsStateWithLifecycle()
    val calendarFixedTimezone by app.settingsStore.calendarFixedTimezone.collectAsStateWithLifecycle()
    val calendarZone = remember(calendarTimezoneMode, calendarFixedTimezone) {
        app.settingsStore.calendarZoneId()
    }
    val scope = rememberCoroutineScope()
    val isEdit = editEventId != null

    val initialDate = initialEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now(calendarZone)
    val zoneNow = calendarZone
    // Default slot: P8 精确预填 > next full hour (today) / 09:00 (another day), 1h long.
    val defaultStart = remember {
        initialStartEpochSecond?.let {
            Instant.ofEpochSecond(it).atZone(zoneNow).toLocalDateTime()
        } ?: if (initialDate == LocalDate.now(calendarZone)) {
            LocalDateTime.now(calendarZone).plusHours(1).withMinute(0).withSecond(0).withNano(0)
        } else {
            initialDate.atTime(9, 0)
        }
    }
    val defaultEnd = remember {
        initialEndEpochSecond?.let {
            Instant.ofEpochSecond(it).atZone(zoneNow).toLocalDateTime()
        } ?: defaultStart.plusMinutes(
            // P8 日历设置:新建默认时长(一次性快照,表单打开后不跟随设置变)。
            app.settingsStore.calendarDefaultDurationMin.value.toLong(),
        )
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultEnd) }
    // P8 日历设置:默认提醒提前量(-1 = 不提醒 → null);编辑态后续被事件值覆盖。
    var reminderMinutes by remember {
        mutableStateOf(app.settingsStore.calendarDefaultReminderMin.value.takeIf { it >= 0 })
    }
    // P2-M3 重复日程(创建限定;编辑重复规则属三选语义,App 端 M3 不做)。
    var repeat by remember { mutableStateOf("") }
    var repeatUntil by remember { mutableStateOf<LocalDate?>(null) }
    // 组织者是参与者列表中的固定成员，但不放进 attendee_entries，避免后端重复。
    var organizer by remember { mutableStateOf<PickedMember?>(null) }
    var attendees by remember { mutableStateOf<List<PickedMember>>(emptyList()) }
    var attendeeRoles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var visibility by remember { mutableStateOf("default") }
    var writableCalendars by remember { mutableStateOf<List<UnifiedCalendarDto>>(emptyList()) }
    var targetCalendarId by remember { mutableStateOf("") }
    var eventTimezone by remember { mutableStateOf(calendarZone.id) }
    // P8:编辑态标记重复日程(加载详情时置位)——重复日程不开放参与者编辑。
    var editIsRecurring by remember { mutableStateOf(false) }

    // 忙闲(与 Web 同口径):只要「在所选时段是否有冲突」这一个布尔,不画时间条。
    // 全天日程没有具体时段,不查也不显示。
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selfBusy by remember { mutableStateOf(false) }
    // 自己的 id 只为「我这个点也有事」这一条提示;拉一次即可。
    var selfId by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    // 视频会议(对标飞书:可移除的一项,而非日程的固有属性)。创建默认开;
    // 编辑态按事件当前有没有房间预填。
    var withVideo by remember { mutableStateOf(true) }
    // P9 实体会议室(与 LiveKit 房间无关)。`roomConflict` 是客户端预判,服务端
    // 409 才是权威 —— 网络失败时刻意不置位,免得误禁用保存。
    var meetingRoom by remember { mutableStateOf<MeetingRoomBriefDto?>(null) }
    var showRoomPicker by remember { mutableStateOf(false) }
    var roomConflict by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // Edit mode starts not-ready until the event loads.
    var loaded by remember { mutableStateOf(!isEdit) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(calendarZone.id, isEdit) {
        if (!isEdit) eventTimezone = calendarZone.id
    }

    // Create mode: treat any user-entered content as unsaved work worth
    // confirming before a back-out. (Edit mode prefills the form, so a
    // meaningful "dirty" check would need an initial snapshot — out of scope.)
    val isDirty = !isEdit && (
        title.isNotBlank() || description.isNotBlank() ||
            attendees.isNotEmpty() ||
            visibility != "default" || repeat.isNotEmpty() || meetingRoom != null
    )
    val handleClose: () -> Unit = { if (isDirty) showDiscardConfirm = true else onClose() }

    BackHandler(enabled = isDirty) { showDiscardConfirm = true }

    // P8:预填参与者 —— 逐个目录补全(并发),失败的 id 静默丢弃并提示一次。
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(prefillAttendeeIds) {
        if (isEdit || prefillAttendeeIds.isEmpty()) return@LaunchedEffect
        val picked = prefillAttendeeIds.distinct().mapNotNull { id ->
            app.directoryRepository.getMember(id).getOrNull()?.let { m ->
                PickedMember(
                    userId = m.id,
                    displayName = m.fullName ?: m.shortName ?: m.email ?: m.id.take(8),
                    email = m.email,
                    avatarUrl = m.avatarUrl,
                )
            }
        }
        val ordinaryAttendees = picked.filterNot { it.userId == organizer?.userId }
        attendees = ordinaryAttendees
        attendeeRoles = ordinaryAttendees.associate { it.userId to "required" }
        if (picked.size < prefillAttendeeIds.distinct().size) {
            android.widget.Toast.makeText(
                context,
                R.string.calendar_prefill_attendee_failed,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    androidx.compose.runtime.LaunchedEffect(initialMeetingRoomId, isEdit) {
        if (isEdit || initialMeetingRoomId.isNullOrBlank()) return@LaunchedEffect
        runCatching { app.apiClient.meetingRoomApi.getRoom(initialMeetingRoomId) }
            .onSuccess { meetingRoom = it.toBrief() }
            .onFailure {
                android.widget.Toast.makeText(
                    context,
                    R.string.meeting_room_prefill_failed,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
    }

    androidx.compose.runtime.LaunchedEffect(editEventId) {
        if (editEventId == null) return@LaunchedEffect
        runCatching { app.apiClient.calendarApi.getEvent(editEventId) }
            .onSuccess { e ->
                val zone = calendarZone
                // All-day events are anchored to their AUTHORED zone's midnight;
                // parse them in that zone (device-TZ parsing shifts the shown day
                // ±1 vs the calendar grid — same bug fixed on the detail page).
                // Timed events keep device wall-clock, matching the timed pickers.
                val eventZone = runCatching { ZoneId.of(e.timezone) }.getOrNull() ?: zone
                val parseZone = eventZone
                title = e.title
                description = e.description
                visibility = e.visibility.takeIf { it in EVENT_VISIBILITIES } ?: "default"
                targetCalendarId = e.displayCalendarId.orEmpty()
                allDay = e.allDay
                eventTimezone = eventZone.id
                val startLdt = if (e.allDay && e.startDate != null) {
                    LocalDate.parse(e.startDate).atStartOfDay()
                } else {
                    OffsetDateTime.parse(e.startAt).atZoneSameInstant(parseZone).toLocalDateTime()
                }
                val endLdt = if (e.allDay && e.endDate != null) {
                    LocalDate.parse(e.endDate).atStartOfDay()
                } else {
                    OffsetDateTime.parse(e.endAt).atZoneSameInstant(parseZone).toLocalDateTime()
                }
                start = startLdt
                // All-day end is stored exclusive (next midnight) → show inclusive last day.
                end = if (e.allDay) endLdt.minusDays(1) else endLdt
                // 后端 push_due_reminders 按 max(reminders) 算触发点并只推一次,
                // 历史多值数据(Web 旧版多选留下的)里生效的是最大那条 —— 取 max
                // 而不是 first,免得编辑时把实际会响的那条改掉。
                reminderMinutes = e.reminders.maxOrNull()
                // 组织者在 UI 中固定展示；普通参与者继续使用可编辑列表和提交载荷。
                // 重复日程不放开(服务端三选路径剔除 attendee_ids)。
                editIsRecurring = e.isRecurring
                withVideo = e.room != null
                meetingRoom = e.meetingRoom
                val organizerAttendee = e.attendees.firstOrNull {
                    it.role == "organizer" || it.id == e.organizer?.id
                }
                val organizerId = e.organizer?.id?.takeIf { it.isNotBlank() }
                    ?: organizerAttendee?.id
                organizer = organizerId?.let { id ->
                    PickedMember(
                        userId = id,
                        displayName = e.organizer?.fullName
                            ?: organizerAttendee?.fullName
                            ?: organizerAttendee?.email
                            ?: "?",
                        email = organizerAttendee?.email,
                        avatarUrl = null,
                    )
                }
                attendees = e.attendees.mapNotNull { a ->
                    val uid = a.id ?: return@mapNotNull null
                    if (a.role == "organizer" || uid == organizerId) return@mapNotNull null
                    PickedMember(
                        userId = uid,
                        displayName = a.fullName ?: a.email ?: "?",
                        email = a.email,
                        avatarUrl = null,
                    )
                }
                attendeeRoles = e.attendees.mapNotNull { a ->
                    val uid = a.id ?: return@mapNotNull null
                    if (a.role == "organizer") return@mapNotNull null
                    uid to if (a.role == "optional") "optional" else "required"
                }.toMap()
                loaded = true
            }
            .onFailure { errorRes = R.string.event_load_error; loaded = true }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val me = runCatching { app.apiClient.userApi.getMe() }.getOrNull()
        selfId = me?.id
        if (!isEdit && me != null) {
            organizer = PickedMember(
                userId = me.id,
                displayName = me.full_name ?: me.short_name ?: me.email ?: "?",
                email = me.email,
                avatarUrl = me.avatar_url.takeIf { it.isNotBlank() },
            )
            attendees = attendees.filterNot { it.userId == me.id }
            attendeeRoles = attendeeRoles - me.id
        }
        writableCalendars = runCatching { app.apiClient.calendarApi.listCalendars() }
            .getOrDefault(emptyList())
            .filter { it.capabilities.canWrite }
        if (!isEdit && targetCalendarId.isBlank()) {
            targetCalendarId = writableCalendars.firstOrNull { it.enabled }?.id
                ?: writableCalendars.firstOrNull()?.id.orEmpty()
        }
    }

    val showFreeBusy = !allDay
    androidx.compose.runtime.LaunchedEffect(
        start,
        end,
        allDay,
        attendees,
        organizer?.userId,
        selfId,
    ) {
        val ids = (
            attendees.map { it.userId } +
                listOfNotNull(organizer?.userId, selfId)
            ).distinct()
        if (allDay || ids.isEmpty()) {
            busyIds = emptySet()
            selfBusy = false
            return@LaunchedEffect
        }
        val zone = runCatching { ZoneId.of(eventTimezone) }.getOrDefault(calendarZone)
        val slotStart = start.atZone(zone).toInstant()
        val slotEnd = end.atZone(zone).toInstant()
        // 窗口取所选开始时刻当天,与 Web 一致(端点限制 ≤31 天)。
        val dayStart = start.toLocalDate().atStartOfDay(zone).toInstant()
        val dayEnd = start.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        runCatching {
            app.apiClient.calendarApi.freeBusy(
                attendeeIds = ids.joinToString(","),
                start = DateTimeFormatter.ISO_INSTANT.format(dayStart),
                end = DateTimeFormatter.ISO_INSTANT.format(dayEnd),
                excludeEventId = editEventId,
            )
        }.onSuccess { res ->
            val conflicting = res.results.filter { entry ->
                entry.busy.any { b ->
                    val bs = runCatching { Instant.parse(b.start) }.getOrNull()
                    val be = runCatching { Instant.parse(b.end) }.getOrNull()
                    bs != null && be != null && bs < slotEnd && be > slotStart
                }
            }.map { it.userId }.toSet()
            busyIds = conflicting
            selfBusy = selfId != null &&
                selfId != organizer?.userId &&
                attendees.none { it.userId == selfId } &&
                conflicting.contains(selfId)
        }.onFailure {
            // 忙闲拿不到不该挡住建日程:静默降级成「都不显示忙」。
            busyIds = emptySet()
            selfBusy = false
        }
    }
    // P9:时段/房间一变就重查可用性。网络失败按「不冲突」处理 —— 与其误禁用
    // 保存按钮,不如让服务端用 409 给出准确答复。
    androidx.compose.runtime.LaunchedEffect(start, end, allDay, meetingRoom?.id) {
        val room = meetingRoom
        if (room == null || allDay) {
            roomConflict = false
            return@LaunchedEffect
        }
        val zone = runCatching { ZoneId.of(eventTimezone) }.getOrDefault(calendarZone)
        val startInstant = start.atZone(zone).toInstant()
        val endInstant = end.atZone(zone).toInstant()
        if (!endInstant.isAfter(startInstant)) {
            roomConflict = false
            return@LaunchedEffect
        }
        roomConflict = runCatching {
            app.apiClient.meetingRoomApi.availability(
                start = isoUtc(startInstant),
                end = isoUtc(endInstant),
                excludeEventId = editEventId,
            ).results.none { it.id == room.id && it.isAvailable }
        }.getOrDefault(false)
    }

    fun submit() {
        if (title.isBlank() || submitting) return
        val zone = runCatching { ZoneId.of(eventTimezone) }.getOrDefault(calendarZone)
        val startInstant = if (allDay) null else start.atZone(zone).toInstant()
        val endInstant = if (allDay) null else end.atZone(zone).toInstant()
        if (
            (allDay && end.toLocalDate().isBefore(start.toLocalDate())) ||
            (!allDay && (startInstant == null || endInstant == null || !endInstant.isAfter(startInstant)))
        ) {
            errorRes = R.string.calendar_error_end_before_start
            return
        }
        submitting = true
        errorRes = null
        scope.launch {
            runCatching {
                val attendeeEntries = attendees
                    .filterNot { it.userId == organizer?.userId }
                    .map { attendee ->
                        AttendeeEntryRequest(
                            userId = attendee.userId,
                            role = attendeeRoles[attendee.userId] ?: "required",
                        )
                    }
                if (isEdit) {
                    app.apiClient.calendarApi.updateEvent(
                        editEventId!!,
                        UpdateEventRequest(
                            title = title.trim(),
                            description = description.trim(),
                            startAt = startInstant?.let(::isoUtc),
                            endAt = endInstant?.let(::isoUtc),
                            startDate = start.toLocalDate().toString().takeIf { allDay },
                            endDate = end.toLocalDate().plusDays(1).toString().takeIf { allDay },
                            allDay = allDay,
                            reminders = reminderMinutes?.let { listOf(it) } ?: emptyList(),
                            // P1-8:结构化参与者携带 required/optional；外部联系人使用真实 user_id。
                            attendeeEntries = if (editIsRecurring) null else attendeeEntries,
                            visibility = visibility,
                            timezone = zone.id,
                            visibilityExplicit = true,
                            editScope = editScope,
                            // P9:全天不允许带房间;"" = 释放(不能用 null,
                            // Moshi 会把它丢掉,后端就当没提过这个字段)。
                            meetingRoomId = if (allDay) "" else meetingRoom?.id.orEmpty(),
                            // 重复日程的系列级编辑不传(服务端会剔除),与
                            // attendeeIds 同档降级。
                            withVideoMeeting = if (editIsRecurring) null else withVideo,
                        ),
                    )
                } else {
                    app.apiClient.calendarApi.createEvent(
                        CreateEventRequest(
                            title = title.trim(),
                            startAt = startInstant?.let(::isoUtc),
                            endAt = endInstant?.let(::isoUtc),
                            startDate = start.toLocalDate().toString().takeIf { allDay },
                            endDate = end.toLocalDate().plusDays(1).toString().takeIf { allDay },
                            allDay = allDay,
                            reminders = reminderMinutes?.let { listOf(it) } ?: emptyList(),
                            attendeeEntries = attendeeEntries,
                            description = description.trim(),
                            visibility = visibility,
                            timezone = zone.id,
                            recurrence = composeRRule(repeat, repeatUntil),
                            sourceConversationId = sourceConversationId,
                            // P9:全天日程 M1 不支持订会议室(服务端也会 400)。
                            meetingRoomId = if (allDay) null else meetingRoom?.id,
                            withVideoMeeting = withVideo,
                            calendarId = targetCalendarId.takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
                .onSuccess { dto ->
                    // P8:创建成功先回调(IM 链路回发日程卡片),再关屏。
                    if (!isEdit) onCreated?.invoke(dto)
                    onClose()
                }
                .onFailure { e ->
                    submitting = false
                    errorRes = when {
                        // P9:409 = 会议室刚被他人订走。区分出来才能给出可行动的
                        // 提示(换一间),泛化成「保存失败」用户无从下手。
                        isMeetingRoomConflict(e) -> R.string.meeting_room_conflict_error
                        isEdit -> R.string.event_update_failed
                        else -> R.string.calendar_create_failed
                    }
                    if (isMeetingRoomConflict(e)) roomConflict = true
                }
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(
                    if (isEdit) R.string.calendar_edit_title else R.string.calendar_create_title
                ),
                onBack = handleClose,
                actions = {
                    TextButton(
                        onClick = { submit() },
                        enabled = title.isNotBlank() && !submitting && loaded,
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = Dimens.SpaceS).height(Dimens.IconSmall),
                            )
                        } else {
                            Text(
                                stringResource(
                                    if (isEdit) R.string.calendar_action_save
                                    else R.string.calendar_action_create
                                )
                            )
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
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                placeholder = { Text(stringResource(R.string.calendar_field_title_hint)) },
                singleLine = true,
                supportingText = {
                    // Blank → explain why Save is disabled; otherwise show the
                    // character budget so the 80-char cap isn't a silent surprise.
                    if (title.isBlank()) {
                        Text(stringResource(R.string.calendar_field_title_required))
                    } else {
                        Text(stringResource(R.string.calendar_char_count, title.length, 80))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.SpaceS),
            )

            // 全天开关已撤(与 Web 对齐)。allDay 仍是事件的字段 —— 编辑既有
            // 全天日程时按它自己的值渲染日期行并原样回传,只是表单里不再给
            // 用户开关;新建恒为定时日程。
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

            if (!isEdit && writableCalendars.isNotEmpty()) {
                TargetCalendarDropdown(
                    calendars = writableCalendars,
                    selectedId = targetCalendarId,
                    onSelect = { targetCalendarId = it },
                )
                HorizontalDivider()
            }
            TimezoneDropdown(
                selected = eventTimezone,
                onSelect = { eventTimezone = it },
            )
            if (allDay) {
                Text(
                    text = stringResource(R.string.calendar_all_day_timezone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            ReminderDropdown(
                selectedMinutes = reminderMinutes,
                onSelect = { reminderMinutes = it },
            )
            HorizontalDivider()

            // P2-M3 重复日程:创建限定(编辑重复规则属三选语义,App 端不做)。
            if (!isEdit) {
                RepeatDropdown(
                    selected = repeat,
                    onSelect = { repeat = it },
                )
                if (repeat.isNotEmpty()) {
                    RepeatUntilRow(
                        until = repeatUntil,
                        defaultDate = start.toLocalDate().plusMonths(1),
                        onPick = { repeatUntil = it },
                    )
                }
                HorizontalDivider()
            }

            EventVisibilityRow(
                visibility = visibility,
                onSelect = { visibility = it },
            )
            HorizontalDivider()

            // P8 编辑增删参与者:创建态 + 非重复日程编辑态(加载完成后)展示;
            // 重复日程编辑不展示(服务端三选路径剔除 attendee_ids)。
            if (!isEdit || (loaded && !editIsRecurring)) {
                // 标题/计数/「添加参与者」同一行,下面一人一行(与 Web 对齐):
                // 头像 + 名字 + 忙/闲 + 行尾 ×。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.SpaceM),
                ) {
                    Text(
                        text = stringResource(
                            R.string.calendar_field_attendees_count,
                            attendees.size + if (organizer != null) 1 else 0,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.calendar_add_attendees))
                    }
                }
                organizer?.let { picked ->
                    CalendarAttendeeRow(
                        picked = picked,
                        isBusy = busyIds.contains(picked.userId),
                        showFreeBusy = showFreeBusy,
                        isOrganizer = true,
                    )
                }
                attendees.forEach { picked ->
                    CalendarAttendeeRow(
                        picked = picked,
                        isBusy = busyIds.contains(picked.userId),
                        showFreeBusy = showFreeBusy,
                        isOrganizer = false,
                        isOptional = attendeeRoles[picked.userId] == "optional",
                        onRoleToggle = {
                            val next = attendeeRoles.toMutableMap()
                            next[picked.userId] = if (
                                next[picked.userId] == "optional"
                            ) "required" else "optional"
                            attendeeRoles = next
                        },
                        onRemove = {
                            attendees = attendees - picked
                            attendeeRoles = attendeeRoles - picked.userId
                        },
                    )
                }
                // 当前用户不在参与者列表中时，仍提示其自身冲突。
                if (showFreeBusy && selfBusy) {
                    Text(
                        text = stringResource(R.string.freebusy_self_busy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                }
                HorizontalDivider()
            }

            // 视频会议 —— 对标飞书,是一项「可以移除」的东西而不是日程的固有
            // 属性。放在会议室之前:两者都是「在哪开」,线上先于线下。
            // 重复日程的系列级编辑不放开(服务端会剔除),与参与者同档。
            if (!editIsRecurring) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.SpaceM),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.calendar_video_meeting),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = stringResource(
                                if (withVideo) {
                                    R.string.calendar_video_meeting_on
                                } else {
                                    R.string.calendar_video_meeting_off
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = withVideo, onCheckedChange = { withVideo = it })
                }
                HorizontalDivider()
            }

            // P9 会议室 —— 放在参与者之后:容量筛选按已选人数起算,可用性依赖
            // 上方选好的时段,冲突提示也就正好挨着保存按钮。
            Text(
                text = stringResource(R.string.meeting_room_field_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Dimens.SpaceM),
            )
            if (allDay) {
                // M1 不支持全天订会议室(服务端同样 400):「按谁的时区的
                // 00:00–24:00」这个问题还没定,先明说而不是让它悄悄失败。
                Text(
                    text = stringResource(R.string.meeting_room_all_day_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.SpaceXs),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.SpaceXs),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        meetingRoom?.let { room ->
                            // 尾部 × 显式表达「移除预订」——原先点 chip 本身移除,
                            // 没有可见入口,与 Web 的 × 也对不上。
                            InputChip(
                                selected = true,
                                onClick = { meetingRoom = null },
                                label = {
                                    Text(
                                        listOfNotNull(
                                            meetingRoomTitle(room.name, room.code),
                                            compactMeetingRoomPathLabel(room.pathLabel)
                                                .takeIf { it.isNotBlank() },
                                        ).joinToString(" · "),
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(
                                            R.string.meeting_room_remove,
                                        ),
                                        modifier = Modifier.size(Dimens.IconTiny),
                                    )
                                },
                            )
                            if (roomConflict) {
                                Text(
                                    text = stringResource(
                                        R.string.meeting_room_conflict_inline,
                                        meetingRoomTitle(room.name, room.code),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } ?: Text(
                            text = stringResource(R.string.meeting_room_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showRoomPicker = true }) {
                        Text(
                            stringResource(
                                if (meetingRoom == null) {
                                    R.string.meeting_room_add
                                } else {
                                    R.string.meeting_room_change
                                },
                            ),
                        )
                    }
                }
            }
            HorizontalDivider()

            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 500) description = it },
                placeholder = { Text(stringResource(R.string.calendar_field_description_hint)) },
                minLines = 3,
                supportingText = {
                    Text(stringResource(R.string.calendar_char_count, description.length, 500))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceM),
            )

            errorRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(Dimens.SpaceXxl))
        }
    }

    if (showPicker) {
        ContactPicker(
            deps = app,
            mode = ContactPickerMode.Multi,
            includeExternal = true,
            excludeUserIds = (
                attendees.map { it.userId } + listOfNotNull(organizer?.userId)
                ).toSet(),
            onConfirm = { picked ->
                val newAttendees = picked.filter { candidate ->
                    candidate.userId != organizer?.userId &&
                        attendees.none { it.userId == candidate.userId }
                }
                attendees = attendees + newAttendees
                attendeeRoles = attendeeRoles +
                    newAttendees.associate { it.userId to "required" }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showRoomPicker) {
        val zone = runCatching { ZoneId.of(eventTimezone) }.getOrDefault(calendarZone)
        MeetingRoomPicker(
            apiClient = app.apiClient,
            startIso = isoUtc(start.atZone(zone).toInstant()),
            endIso = isoUtc(end.atZone(zone).toInstant()),
            excludeEventId = editEventId,
            // 组织者自己也占一个位子。
            seedCapacity = attendees.size + 1,
            workingStartMin = workingHours.startMin,
            workingEndMin = workingHours.endMin,
            onConfirm = { room ->
                meetingRoom = room
                showRoomPicker = false
            },
            onDismiss = { showRoomPicker = false },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.calendar_discard_title)) },
            text = { Text(stringResource(R.string.calendar_discard_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onClose() }) {
                    Text(
                        text = stringResource(R.string.calendar_discard_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun CalendarAttendeeRow(
    picked: PickedMember,
    isBusy: Boolean,
    showFreeBusy: Boolean,
    isOrganizer: Boolean,
    isOptional: Boolean = false,
    onRoleToggle: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXxs)
            .clip(RoundedCornerShape(Dimens.CornerS))
            .background(
                if (isBusy) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(start = Dimens.SpaceS, top = Dimens.SpaceXs, bottom = Dimens.SpaceXs),
    ) {
        MemberAvatar(
            name = picked.displayName,
            url = picked.avatarUrl,
            cacheKey = "avatar:${picked.userId}",
            size = Dimens.AvatarXs,
        )
        Text(
            text = picked.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isBusy) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceS),
        )
        if (showFreeBusy) {
            Text(
                text = stringResource(
                    if (isBusy) R.string.freebusy_busy else R.string.freebusy_free,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (isBusy) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isOrganizer) {
            Text(
                text = stringResource(R.string.event_organizer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceM),
            )
        } else {
            TextButton(onClick = { onRoleToggle?.invoke() }) {
                Text(
                    stringResource(
                        if (isOptional) R.string.calendar_attendee_optional
                        else R.string.calendar_attendee_required,
                    ),
                )
            }
            IconButton(
                onClick = { onRemove?.invoke() },
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.calendar_attendee_remove,
                        picked.displayName,
                    ),
                    modifier = Modifier.size(Dimens.IconTiny),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            .padding(vertical = Dimens.SpaceM),
    ) {
        Text(label)
        Row {
            Text(
                text = value.toLocalDate().format(dateFmt),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showDate = true },
            )
            if (!allDay) {
                Spacer(Modifier.padding(start = Dimens.SpaceM))
                Text(
                    text = value.toLocalTime().format(timeFmt),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = Dimens.SpaceM)
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

/**
 * P2-M3 重复日程 RRULE 组装(与 Web CreateEventDialog 同一口径):
 * UNTIL 用「浮动本地时刻」(无 Z)——后端按事件时区墙上钟展开,且 dateutil
 * 在 naive dtstart 下拒绝 UTC(Z)形式的 UNTIL。
 */
private fun composeRRule(repeat: String, until: LocalDate?): String {
    if (repeat.isEmpty()) return ""
    var rule = if (repeat == "WEEKDAYS") {
        "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    } else {
        "FREQ=$repeat"
    }
    if (until != null) {
        rule += ";UNTIL=" + until.format(DateTimeFormatter.BASIC_ISO_DATE) + "T235959"
    }
    return rule
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY")

    @Composable
    fun labelFor(key: String): String = when (key) {
        "DAILY" -> stringResource(R.string.calendar_repeat_daily)
        "WEEKDAYS" -> stringResource(R.string.calendar_repeat_weekdays)
        "WEEKLY" -> stringResource(R.string.calendar_repeat_weekly)
        "MONTHLY" -> stringResource(R.string.calendar_repeat_monthly)
        else -> stringResource(R.string.calendar_repeat_none)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(stringResource(R.string.calendar_field_repeat))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            ) {
                Text(labelFor(selected))
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // 锚点是窄按钮，不能让菜单跟着它收窄，否则「每个工作日」这类选项会折行
                matchTextFieldWidth = false,
            ) {
                options.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(labelFor(key), softWrap = false) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatUntilRow(
    until: LocalDate?,
    defaultDate: LocalDate,
    onPick: (LocalDate?) -> Unit,
) {
    var showDate by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(stringResource(R.string.calendar_repeat_until))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showDate = true }) {
                Text(
                    until?.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        ?: stringResource(R.string.calendar_repeat_until_none)
                )
            }
            if (until != null) {
                TextButton(onClick = { onPick(null) }) {
                    Text(stringResource(R.string.calendar_repeat_until_clear))
                }
            }
        }
    }

    // M3 date picker (matches DateTimeRow) — the earlier platform dialog was
    // the only non-M3 picker on this screen.
    if (showDate) {
        val base = until ?: defaultDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = base.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDropdown(selectedMinutes: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // 「不提醒」= null 排首位,其余档位读共享的 REMINDER_OPTIONS(与 Web 同一份)。
    // 历史数据里的非标准值(45 这种)追加进去,免得下拉里选不中当前值。
    val options: List<Int?> = remember(selectedMinutes) {
        val extra = selectedMinutes?.takeIf { it !in REMINDER_OPTIONS }
        listOf<Int?>(null) + (REMINDER_OPTIONS + listOfNotNull(extra)).sorted()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(stringResource(R.string.calendar_field_reminder))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            ) {
                Text(reminderLabel(selectedMinutes))
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                matchTextFieldWidth = false,
            ) {
                options.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(reminderLabel(minutes), softWrap = false) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetCalendarDropdown(
    calendars: List<UnifiedCalendarDto>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.id == selectedId } ?: calendars.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.calendar_target)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar.displayName) },
                    onClick = {
                        onSelect(calendar.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimezoneDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.calendar_field_timezone)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            calendarTimezoneOptions(selected).forEach { timezone ->
                DropdownMenuItem(
                    text = { Text(timezone) },
                    onClick = {
                        onSelect(timezone)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventVisibilityRow(visibility: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labelRes = when (visibility) {
        "public" -> R.string.calendar_visibility_public
        "private" -> R.string.calendar_visibility_private
        else -> R.string.calendar_visibility_default
    }
    val hintRes = when (visibility) {
        "public" -> R.string.calendar_visibility_public_hint
        "private" -> R.string.calendar_visibility_private_hint
        else -> R.string.calendar_visibility_default_hint
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceM),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.calendar_visibility_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            ) {
                Text(stringResource(labelRes), softWrap = false)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                matchTextFieldWidth = false,
            ) {
                EVENT_VISIBILITIES.forEach { value ->
                    val itemLabel = when (value) {
                        "public" -> R.string.calendar_visibility_public
                        "private" -> R.string.calendar_visibility_private
                        else -> R.string.calendar_visibility_default
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(itemLabel), softWrap = false) },
                        onClick = {
                            onSelect(value)
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

/**
 * P9:409 = 该会议室在这个时段已被占用(core/api/calendar.py 的
 * `meeting_room_unavailable`)。日程接口没有别的 409 语义,所以状态码本身
 * 就够判定,不必解析响应体。
 */
private fun isMeetingRoomConflict(e: Throwable): Boolean =
    e is HttpException && e.code() == 409
