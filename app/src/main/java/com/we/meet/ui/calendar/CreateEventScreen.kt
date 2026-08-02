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
import com.we.meet.R
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.PickedMember
import com.we.meet.data.api.dto.CreateEventRequest
import com.we.meet.data.api.dto.MeetingRoomBriefDto
import com.we.meet.data.api.dto.UpdateEventRequest
import com.we.meet.ui.meetingroom.MeetingRoomPicker
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
    /** P8:创建成功回调(仅创建模式,编辑不触发)——IM 链路用来回发日程卡片。 */
    onCreated: ((com.we.meet.data.api.dto.CalendarEventDto) -> Unit)? = null,
    /** P8:来源 IM 会话 cid,随创建落库(M3 变更推送用);非 IM 链路为 null。 */
    sourceConversationId: String? = null,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()
    val isEdit = editEventId != null

    val initialDate = initialEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
    val zoneNow = ZoneId.systemDefault()
    // Default slot: P8 精确预填 > next full hour (today) / 09:00 (another day), 1h long.
    val defaultStart = remember {
        initialStartEpochSecond?.let {
            Instant.ofEpochSecond(it).atZone(zoneNow).toLocalDateTime()
        } ?: if (initialDate == LocalDate.now()) {
            LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
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
    var attendees by remember { mutableStateOf<List<PickedMember>>(emptyList()) }
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

    // Create mode: treat any user-entered content as unsaved work worth
    // confirming before a back-out. (Edit mode prefills the form, so a
    // meaningful "dirty" check would need an initial snapshot — out of scope.)
    val isDirty = !isEdit && (
        title.isNotBlank() || description.isNotBlank() ||
            attendees.isNotEmpty() || repeat.isNotEmpty() || meetingRoom != null
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
        attendees = picked
        if (picked.size < prefillAttendeeIds.distinct().size) {
            android.widget.Toast.makeText(
                context,
                R.string.calendar_prefill_attendee_failed,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    androidx.compose.runtime.LaunchedEffect(editEventId) {
        if (editEventId == null) return@LaunchedEffect
        runCatching { app.apiClient.calendarApi.getEvent(editEventId) }
            .onSuccess { e ->
                val zone = ZoneId.systemDefault()
                // All-day events are anchored to their AUTHORED zone's midnight;
                // parse them in that zone (device-TZ parsing shifts the shown day
                // ±1 vs the calendar grid — same bug fixed on the detail page).
                // Timed events keep device wall-clock, matching the timed pickers.
                val eventZone = runCatching { ZoneId.of(e.timezone) }.getOrNull() ?: zone
                val parseZone = if (e.allDay) eventZone else zone
                title = e.title
                description = e.description
                allDay = e.allDay
                val startLdt = OffsetDateTime.parse(e.startAt).atZoneSameInstant(parseZone).toLocalDateTime()
                val endLdt = OffsetDateTime.parse(e.endAt).atZoneSameInstant(parseZone).toLocalDateTime()
                start = startLdt
                // All-day end is stored exclusive (next midnight) → show inclusive last day.
                end = if (e.allDay) endLdt.minusDays(1) else endLdt
                reminderMinutes = e.reminders.firstOrNull()
                // P8 编辑增删参与者:预填既有参与者(组织者恒在,不进列表);
                // 重复日程不放开(服务端三选路径剔除 attendee_ids)。
                editIsRecurring = e.isRecurring
                withVideo = e.room != null
                meetingRoom = e.meetingRoom
                attendees = e.attendees.mapNotNull { a ->
                    val uid = a.id ?: return@mapNotNull null
                    if (a.role == "organizer") return@mapNotNull null
                    PickedMember(
                        userId = uid,
                        displayName = a.fullName ?: a.email ?: "?",
                        email = a.email,
                        avatarUrl = null,
                    )
                }
                loaded = true
            }
            .onFailure { errorRes = R.string.event_load_error; loaded = true }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        selfId = runCatching { app.apiClient.userApi.getMe() }.getOrNull()?.id
    }

    val showFreeBusy = !allDay
    androidx.compose.runtime.LaunchedEffect(start, end, allDay, attendees, selfId) {
        if (allDay || attendees.isEmpty()) {
            busyIds = emptySet()
            selfBusy = false
            return@LaunchedEffect
        }
        val zone = ZoneId.systemDefault()
        val slotStart = start.atZone(zone).toInstant()
        val slotEnd = end.atZone(zone).toInstant()
        // 窗口取所选开始时刻当天,与 Web 一致(端点限制 ≤31 天)。
        val dayStart = start.toLocalDate().atStartOfDay(zone).toInstant()
        val dayEnd = start.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val ids = (attendees.map { it.userId } + listOfNotNull(selfId)).distinct()
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
            selfBusy = selfId != null && conflicting.contains(selfId)
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
        val zone = ZoneId.systemDefault()
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
                if (isEdit) {
                    app.apiClient.calendarApi.updateEvent(
                        editEventId!!,
                        UpdateEventRequest(
                            title = title.trim(),
                            description = description.trim(),
                            startAt = isoUtc(startInstant),
                            endAt = isoUtc(endInstant),
                            allDay = allDay,
                            reminders = reminderMinutes?.let { listOf(it) } ?: emptyList(),
                            // P8:非重复日程全量同步参与者;重复日程不传(null)。
                            attendeeIds = if (editIsRecurring) null
                            else attendees.map { it.userId },
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
                            startAt = isoUtc(startInstant),
                            endAt = isoUtc(endInstant),
                            allDay = allDay,
                            reminders = reminderMinutes?.let { listOf(it) } ?: emptyList(),
                            attendeeIds = attendees.map { it.userId },
                            description = description.trim(),
                            timezone = zone.id,
                            recurrence = composeRRule(repeat, repeatUntil),
                            sourceConversationId = sourceConversationId,
                            // P9:全天日程 M1 不支持订会议室(服务端也会 400)。
                            meetingRoomId = if (allDay) null else meetingRoom?.id,
                            withVideoMeeting = withVideo,
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceS),
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

            // P2-M3 重复日程:创建限定(编辑重复规则属三选语义,App 端不做)。
            if (!isEdit) {
                RepeatDropdown(
                    selected = repeat,
                    onSelect = { repeat = it },
                )
                if (repeat.isNotEmpty()) {
                    RepeatUntilRow(
                        until = repeatUntil,
                        onPick = { repeatUntil = it },
                    )
                }
                HorizontalDivider()
            }

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
                            attendees.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.calendar_add_attendees))
                    }
                }
                attendees.forEach { picked ->
                    val isBusy = busyIds.contains(picked.userId)
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
                                    if (isBusy) R.string.freebusy_busy
                                    else R.string.freebusy_free,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isBusy) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { attendees = attendees - picked },
                            modifier = Modifier.size(Dimens.IconButtonCompact),
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
                // 发起人自己不在参与人列表里,但「我这个点也有事」同样该提醒。
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
                                            room.name,
                                            room.pathLabel?.takeIf { it.isNotBlank() },
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
                                        room.name,
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
            excludeUserIds = attendees.map { it.userId }.toSet(),
            onConfirm = { picked ->
                attendees = (attendees + picked).distinctBy { it.userId }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showRoomPicker) {
        val zone = ZoneId.systemDefault()
        MeetingRoomPicker(
            apiClient = app.apiClient,
            startIso = isoUtc(start.atZone(zone).toInstant()),
            endIso = isoUtc(end.atZone(zone).toInstant()),
            excludeEventId = editEventId,
            // 组织者自己也占一个位子。
            seedCapacity = attendees.size + 1,
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
            ) {
                options.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(labelFor(key)) },
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
private fun RepeatUntilRow(until: LocalDate?, onPick: (LocalDate?) -> Unit) {
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
        val base = until ?: LocalDate.now().plusMonths(1)
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
            .padding(vertical = Dimens.SpaceS),
    ) {
        Text(stringResource(R.string.calendar_field_reminder))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
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

/**
 * P9:409 = 该会议室在这个时段已被占用(core/api/calendar.py 的
 * `meeting_room_unavailable`)。日程接口没有别的 409 语义,所以状态码本身
 * 就够判定,不必解析响应体。
 */
private fun isMeetingRoomConflict(e: Throwable): Boolean =
    e is HttpException && e.code() == 409
