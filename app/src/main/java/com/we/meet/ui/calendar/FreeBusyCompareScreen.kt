package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.data.api.dto.BusyIntervalDto
import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.ui.calendar.views.TimeBlock
import com.we.meet.ui.calendar.views.TimeSelection
import com.we.meet.ui.calendar.views.TimelineScaffold
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 忙闲页一列 = 一个人;freebusy results 缺席该 id = 跨组织不可见。 */
private data class PersonColumn(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val isSelf: Boolean,
)

/** 单次 freebusy 请求的人数上限(attendee_ids 走 query string)。 */
private const val MAX_IDS = 50

/** 列宽下限(飞书样式:一屏约 4 列,超出整体横滚)。 */
private val MIN_COL_WIDTH = Dimens.Calendar.MinColumnWidth

/** 底部冲突提示里最多列几个名字(超出补「等」;列头红点仍标出每一个人)。 */
private const val CONFLICT_NAMES_SHOWN = 3

// 回复状态取值(后端 EventAttendee.rsvp);declined 不会出现在忙闲里 ——
// freebusy 查询本身就 exclude 掉了拒绝的日程。
private const val RSVP_ACCEPTED = "accepted"
private const val RSVP_NEEDS_ACTION = "needs_action"

/**
 * 拒绝档。freebusy 里不会出现(后端按 rsvp≠declined 过滤),忙闲页的「已拒绝」
 * 块是从我自己的日程 + attendees 表态反推出来的,纯展示不占忙闲。
 */
private const val RSVP_DECLINED = "declined"

/** 撞上所选时段的人:头像角标红点(与选段冲突色同档)。 */

/**
 * P8 忙闲对比页(对标飞书「查看日历/群成员日历」,单聊/群聊共用):
 * 头像列头在表格内与该列严格对齐,列宽弹性有下限,列多时列头+网格整体
 * 横向滚动;右上角「选择成员」进独立选择页(默认全选,「我」恒选)。
 * 点空白 = 30min 吸附起点的 1 小时选段(±30min 微调),底部确认条给出
 * 「所有参与者都有空 / N 人忙碌」,「创建日程」带 时段+勾选成员 预填跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeBusyCompareScreen(
    userIds: List<String>,
    title: String,
    onBack: () -> Unit,
    onCreateEvent: (startEpochSecond: Long, endEpochSecond: Long, attendeeIds: List<String>) -> Unit,
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val zone = remember { ZoneId.systemDefault() }

    // ── 身份(拉一次):me 优先 + 目录补全;失败的列退 id 前 8 位。 ──
    var people by remember { mutableStateOf<List<PersonColumn>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var identityLoading by remember { mutableStateOf(true) }
    LaunchedEffect(userIds) {
        runCatching {
            coroutineScope {
                val me = async {
                    runCatching { app.apiClient.userApi.getMe() }.getOrNull()
                }.await()
                val others = userIds.filter { it.isNotBlank() && it != me?.id }
                    .distinct()
                    .take(MAX_IDS - 1)
                    .map { id ->
                        async {
                            val m = app.directoryRepository.getMember(id).getOrNull()
                            PersonColumn(
                                userId = id,
                                name = m?.fullName ?: m?.shortName ?: id.take(8),
                                avatarUrl = m?.avatarUrl,
                                isSelf = false,
                            )
                        }
                    }.map { it.await() }
                val self = me?.let {
                    PersonColumn(
                        userId = it.id,
                        name = it.full_name ?: it.short_name ?: it.id.take(8),
                        avatarUrl = it.avatar_url.takeIf { u -> u.isNotBlank() },
                        isSelf = true,
                    )
                }
                people = listOfNotNull(self) + others
            }
        }
        // 飞书默认全选;列多靠横滚承载。
        checked = people.map { it.userId }.toSet()
        identityLoading = false
    }

    // ── 日期 + 勾选 → 忙闲重拉。busyMap 缺 key = 不可见。 ──
    var day by remember { mutableStateOf(LocalDate.now()) }
    var busyMap by remember { mutableStateOf<Map<String, List<BusyIntervalDto>>?>(null) }
    // Distinguish a fetch failure from "everyone's calendar is invisible": the
    // old code degraded failures to emptyMap(), which read as unavailable and
    // offered no retry. Keep busyMap null on failure and flag the error.
    var busyError by remember { mutableStateOf(false) }
    var busyReloadKey by remember { mutableStateOf(0) }
    val checkedPeople = remember(people, checked) {
        people.filter { checked.contains(it.userId) }
    }
    LaunchedEffect(day, checked, people, busyReloadKey) {
        if (checkedPeople.isEmpty()) return@LaunchedEffect
        busyMap = null
        busyError = false
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        runCatching {
            app.apiClient.calendarApi.freeBusy(
                attendeeIds = checkedPeople.joinToString(",") { it.userId },
                start = DateTimeFormatter.ISO_INSTANT.format(dayStart),
                end = DateTimeFormatter.ISO_INSTANT.format(dayEnd),
            )
        }.onSuccess { res ->
            busyMap = res.results.associate { it.userId to it.busy }
            busyError = false
        }.onFailure {
            busyError = true
        }
    }

    // ── 我当日的日程(我组织的 + 我参加的)。freebusy 只给区间不给标题(刻意
    // 不泄露他人日程内容),但**我自己有权看到的这些**可以贴回去:自己列显示
    // 标题,他人列显示「他对我这场会的回复状态」——后者本来就能在日程详情里
    // 看到,不是新增泄露。拉失败就退回纯灰块,不影响忙闲主流程。 ──
    var myEvents by remember { mutableStateOf<List<CalendarEventDto>>(emptyList()) }
    LaunchedEffect(day, busyReloadKey) {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        myEvents = runCatching {
            app.apiClient.calendarApi.listEvents(
                start = DateTimeFormatter.ISO_INSTANT.format(dayStart),
                end = DateTimeFormatter.ISO_INSTANT.format(dayEnd),
            ).results
        }.getOrDefault(emptyList())
    }

    // 起止(当日分钟制)→ 我的日程。**要求起止完全一致**才算同一场:后端的
    // busy 只合并重叠区间、首尾相接保留边界,所以不重叠时对得上;真重叠了就
    // 对不上 → 退回灰块,宁可不显示也不猜错。
    val myEventByRange: Map<Pair<Int, Int>, CalendarEventDto> =
        remember(myEvents, day, zone) {
            val startOfDay = day.atStartOfDay(zone)
            fun minuteOf(iso: String): Int? = runCatching {
                java.time.Duration.between(
                    startOfDay,
                    OffsetDateTime.parse(iso).toInstant().atZone(zone),
                ).toMinutes().toInt()
            }.getOrNull()
            buildMap {
                myEvents.forEach { e ->
                    val s = minuteOf(e.startAt)?.coerceIn(0, 1440) ?: return@forEach
                    val en = minuteOf(e.endAt)?.coerceIn(0, 1440) ?: return@forEach
                    if (en > s) put(s to en, e)
                }
            }
        }

    /**
     * 这个人在这段忙碌里的回复状态 —— 仅当该区间正好是「我有权看到的一场会」
     * 且他确实在参与者(或组织者)里时才有值;否则 null = 只画灰块。
     */
    fun rsvpOf(event: CalendarEventDto, userId: String, isSelf: Boolean): String? = when {
        isSelf -> event.myRsvp ?: RSVP_ACCEPTED
        else -> event.attendees.firstOrNull { it.id == userId }?.rsvp
            ?: RSVP_ACCEPTED.takeIf { event.organizer?.id == userId }
    }

    // ── 忙碌区间统一解析成当日分钟制:时间轴渲染 / 冲突判定 / 推荐时段共用
    // 这一份,不再各自解析一遍 ISO 串(口径也就必然一致)。 ──
    val busyByPerson: Map<String, List<BusyRange>> = remember(busyMap, day, zone) {
        val startOfDay = day.atStartOfDay(zone)
        fun minuteOf(iso: String): Int? = runCatching {
            java.time.Duration.between(
                startOfDay,
                OffsetDateTime.parse(iso).toInstant().atZone(zone),
            ).toMinutes().toInt()
        }.getOrNull()
        busyMap.orEmpty().mapValues { (_, list) ->
            list.mapNotNull { b ->
                val s = minuteOf(b.start)?.coerceIn(0, 1440) ?: return@mapNotNull null
                val e = minuteOf(b.end)?.coerceIn(0, 1440) ?: return@mapNotNull null
                if (e <= s) null else BusyRange(s, e)
            }
        }
    }

    val visibleChecked = checkedPeople.filter { busyMap?.containsKey(it.userId) == true }
    val invisibleCount = if (busyMap == null) 0 else checkedPeople.size - visibleChecked.size
    val peopleBusy = remember(visibleChecked, busyByPerson) {
        visibleChecked.map { PersonBusyMinutes(it.userId, busyByPerson[it.userId].orEmpty()) }
    }

    // ── 时段选择:点空白 = 30min 吸附起点 + 日历设置里的「日程默认时长」;
    // 之后拖上下圆抓手改起止 / 拖框本体整体移位(与日历模块同一套手势)。 ──
    val defaultDurationMin by app.settingsStore.calendarDefaultDurationMin
        .collectAsStateWithLifecycle()
    val pendingLabel = stringResource(R.string.freebusy_rsvp_pending)
    val declinedLabel = stringResource(R.string.freebusy_rsvp_declined)
    val untitledLabel = stringResource(R.string.calendar_untitled)
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
    LaunchedEffect(day) { selection = null }

    // 冲突不再只报数字:点出是谁忙(对齐 Web「N 人该时段忙碌:张三、李四」)。
    val conflictIds = selection
        ?.let { busyPeopleInRange(peopleBusy, it.startMin, it.endMin) }
        .orEmpty()
    val conflictNames = checkedPeople.filter { conflictIds.contains(it.userId) }.map { it.name }

    // ── 推荐时段(全员空闲;口径与 Web freeSlots.ts 同一份算法)。 ──
    val suggestions = remember(peopleBusy, defaultDurationMin, day) {
        if (peopleBusy.size < 2) emptyList()
        else suggestCommonSlots(
            people = peopleBusy,
            durationMin = defaultDurationMin,
            nowMinuteOfDay = if (day == LocalDate.now()) {
                LocalTime.now().let { it.hour * 60 + it.minute }
            } else null,
        )
    }

    // ── 时间轴的列(一人一列)。对得上「我的一场会」的区间贴标题 + 该人的回复
    // 状态;对不上(他自己的别的日程)只给纯灰块 + 时段。 ──
    val busyColumns: List<List<TimeBlock>> = remember(
        checkedPeople, busyByPerson, myEventByRange, myEvents,
        pendingLabel, untitledLabel, declinedLabel,
    ) {
        val startOfDay = day.atStartOfDay(zone)
        fun minuteOf(iso: String): Int? = runCatching {
            java.time.Duration.between(
                startOfDay,
                OffsetDateTime.parse(iso).toInstant().atZone(zone),
            ).toMinutes().toInt()
        }.getOrNull()

        /**
         * 「他拒了我这场会」的块。拒绝的日程**不在 freebusy 里**(后端按
         * rsvp≠declined 过滤 —— 拒了就不占他的时间,这是对的),所以这批块只能
         * 从我自己的日程反推。不加的话「已拒绝」和「新入群、压根没被邀请」在
         * 组织者眼里都是一片空白,分不开 —— 而组织者恰恰最关心逐人回复。
         * 纯展示:不进 peopleBusy,不参与冲突判定,也不阻塞推荐时段。
         */
        fun declinedBlocks(p: PersonColumn): List<TimeBlock> = myEvents.mapNotNull { e ->
            // 全天日程会铺满整列、把这一列的忙闲全遮住;已取消的会「谁拒了」也没
            // 意义 —— 两者都不画。
            if (e.allDay || e.status.equals("cancelled", ignoreCase = true)) {
                return@mapNotNull null
            }
            val declined = if (p.isSelf) {
                e.myRsvp == RSVP_DECLINED
            } else {
                e.attendees.firstOrNull { it.id == p.userId }?.rsvp == RSVP_DECLINED
            }
            if (!declined) return@mapNotNull null
            val s = minuteOf(e.startAt)?.coerceIn(0, 1440) ?: return@mapNotNull null
            val en = minuteOf(e.endAt)?.coerceIn(0, 1440) ?: return@mapNotNull null
            if (en <= s) return@mapNotNull null
            TimeBlock(
                startMin = s,
                endMin = en,
                // 他人列写状态(组织者要的就是「这人拒了」);自己列写标题 ——
                // 自己拒过的会,认得标题比看到「已拒绝」有用。
                label = if (p.isSelf) {
                    e.title.takeIf { it.isNotBlank() } ?: untitledLabel
                } else declinedLabel,
                timeLabel = "%02d:%02d – %02d:%02d".format(s / 60, s % 60, en / 60, en % 60),
                key = "declined-${e.id}-${p.userId}",
                rsvp = RSVP_DECLINED,
            )
        }

        checkedPeople.map { p ->
            // 拒绝块先入列 → 真忙碌的块叠在它上面(万一那个点他另有安排)。
            declinedBlocks(p) + busyByPerson[p.userId].orEmpty().mapIndexed { i, b ->
                val ev = myEventByRange[b.startMin to b.endMin]
                val rsvp = ev?.let { rsvpOf(it, p.userId, p.isSelf) }
                val pending = rsvp == RSVP_NEEDS_ACTION
                val title = ev?.title?.takeIf { it.isNotBlank() } ?: untitledLabel
                TimeBlock(
                    startMin = b.startMin,
                    endMin = b.endMin,
                    // **他人**未回复的块写「未回复」而不是标题(对齐飞书):我关心
                    // 的是「这个冲突是软的」,状态比标题更该被一眼看到。自己列照
                    // 常写标题 —— 自己的会当然认得,未回复由斜纹表达就够。
                    label = when {
                        rsvp == null -> null
                        pending && !p.isSelf -> pendingLabel
                        else -> title
                    },
                    timeLabel = "%02d:%02d – %02d:%02d".format(
                        b.startMin / 60, b.startMin % 60, b.endMin / 60, b.endMin % 60,
                    ),
                    key = "busy-${p.userId}-$i",
                    rsvp = rsvp,
                    hatched = pending,
                )
            }
        }
    }
    // 点块提示用:短块写不下时段文字,点一下弹「标题 · 时段」/「时段」。
    val blockTimeLabels: Map<String, String> = remember(busyColumns) {
        buildMap {
            busyColumns.forEach { col ->
                col.forEach { b ->
                    val time = b.timeLabel.orEmpty()
                    put(b.key, b.label?.let { "$it · $time" } ?: time)
                }
            }
        }
    }

    val hourHeight = Dimens.Calendar.HourHeight
    val conflictColor = WeMeetTheme.extras.calendar.conflict
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        scrollState.scrollTo(with(density) { (hourHeight * 8).toPx() }.toInt())
    }

    // ── 选择成员页(飞书:右上角入口,独立页,确定后生效)。 ──
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) {
        MemberPickerPage(
            people = people,
            initial = checked,
            onConfirm = { picked ->
                checked = picked
                pickerOpen = false
            },
            onBack = { pickerOpen = false },
        )
        return
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { pickerOpen = true }) {
                        Icon(
                            Icons.Filled.HowToReg,
                            contentDescription = stringResource(R.string.freebusy_pick_members),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 日期条:‹ 今天 › + M/d 周X
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceS),
            ) {
                IconButton(onClick = { day = day.minusDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
                TextButton(onClick = { day = LocalDate.now() }) {
                    Text(stringResource(R.string.calendar_today))
                }
                IconButton(onClick = { day = day.plusDays(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
                Spacer(Modifier.width(Dimens.SpaceXs))
                Text(
                    text = "${day.monthValue}/${day.dayOfMonth} " +
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            when {
                identityLoading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                people.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.freebusy_load_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Box(modifier = Modifier.weight(1f)) {
                        TimelineScaffold(
                            columns = busyColumns,
                            hourHeight = hourHeight,
                            scrollState = scrollState,
                            nowMinute = if (day == LocalDate.now()) {
                                LocalTime.now().let { it.hour * 60 + it.minute }
                            } else null,
                            disabledColumn = { i ->
                                busyMap != null &&
                                    busyMap?.containsKey(checkedPeople[i].userId) != true
                            },
                            selection = selection,
                            selectionConflict = conflictIds.isNotEmpty(),
                            // 短块写不下时段文字 → 点一下用 Snackbar 补(块内已
                            // 显示的也照样弹,行为统一好预期)。
                            onBlockTap = { _, key ->
                                blockTimeLabels[key]?.let { label ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(label)
                                    }
                                }
                            },
                            // 拖抓手改起止 / 拖框整体移位(TimeGrid 里与预选框同一套)。
                            onSelectionAdjust = { selection = it },
                            onSlotTap = { _, minute ->
                                // 起点向下吸附到 30min 格,越过当日末尾整体前移(保时长)。
                                val start = ((minute / 30) * 30)
                                    .coerceIn(0, 1440 - defaultDurationMin)
                                selection =
                                    TimeSelection(start, start + defaultDurationMin)
                            },
                            // 列窄(76dp 起):有标题/状态的块只显那一行,时刻由纵向
                            // 位置 + 左侧刻度读 —— 再塞一行时间就挤成两行了。无标题
                            // 的灰块不受此限(时段是它唯一的信息)。
                            compactBlocks = true,
                            // P8-UX:列头在表格内与列对齐,列多整体横滚(飞书)。
                            minColumnWidth = MIN_COL_WIDTH,
                            columnHeader = { i ->
                                val p = checkedPeople[i]
                                val invisible = busyMap != null &&
                                    busyMap?.containsKey(p.userId) != true
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Dimens.SpaceXs),
                                ) {
                                    // 撞上所选时段的人:头像右上角红点 —— 底部只报
                                    // 名字的话,列多时还得自己扫哪一列压住了。
                                    Box {
                                        MemberAvatar(
                                            name = p.name,
                                            url = p.avatarUrl,
                                            cacheKey = "avatar:${p.userId}",
                                            size = Dimens.AvatarS,
                                        )
                                        if (conflictIds.contains(p.userId)) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(Dimens.Calendar.ConflictDotSize)
                                                    .background(
                                                        conflictColor,
                                                        androidx.compose.foundation.shape
                                                            .CircleShape,
                                                    )
                                                    .border(
                                                        Dimens.Calendar.ConflictDotRing,
                                                        MaterialTheme.colorScheme.surface,
                                                        androidx.compose.foundation.shape
                                                            .CircleShape,
                                                    ),
                                            )
                                        }
                                    }
                                    Text(
                                        text = p.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        color = if (invisible) {
                                            MaterialTheme.colorScheme.outline
                                        } else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = Dimens.SpaceXxs),
                                    )
                                    if (invisible) {
                                        Text(
                                            text = stringResource(
                                                R.string.freebusy_unavailable,
                                            ),
                                            style = WeMeetTextStyles.LabelMicro,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            },
                        )
                        if (busyMap == null && checkedPeople.isNotEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (busyError) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            stringResource(R.string.freebusy_load_error),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Button(
                                            onClick = { busyReloadKey += 1 },
                                            modifier = Modifier.padding(top = Dimens.SpaceS),
                                        ) {
                                            Text(stringResource(R.string.common_retry))
                                        }
                                    }
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }

                    // 底部确认条。
                    Surface(shadowElevation = Dimens.ElevationSticky) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                        ) {
                            // 推荐时段(全员空闲):点一下即套用,免得自己扫空档。
                            if (suggestions.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.freebusy_suggest_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = Dimens.SpaceXxs),
                                ) {
                                    suggestions.forEach { s ->
                                        SuggestionChip(
                                            onClick = {
                                                selection =
                                                    TimeSelection(s.startMin, s.endMin)
                                            },
                                            label = {
                                                Text(
                                                    text = "%02d:%02d - %02d:%02d".format(
                                                        s.startMin / 60, s.startMin % 60,
                                                        s.endMin / 60, s.endMin % 60,
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                            modifier = Modifier.padding(end = Dimens.SpaceXs),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(Dimens.SpaceXs))
                            }
                            val sel = selection
                            if (sel == null) {
                                Text(
                                    stringResource(R.string.freebusy_pick_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "%02d:%02d - %02d:%02d".format(
                                        sel.startMin / 60, sel.startMin % 60,
                                        sel.endMin / 60, sel.endMin % 60,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                // 名字列不下就截断,但**必须带「等」** —— 只截名字
                                // 不改文案的话,「6 人忙碌:三个名字」看着像漏了人。
                                val verdict = when {
                                    conflictNames.isEmpty() ->
                                        stringResource(R.string.freebusy_all_free)

                                    conflictNames.size > CONFLICT_NAMES_SHOWN -> stringResource(
                                        R.string.freebusy_conflict_names_more,
                                        conflictNames.size,
                                        conflictNames.take(CONFLICT_NAMES_SHOWN)
                                            .joinToString("、"),
                                    )

                                    else -> stringResource(
                                        R.string.freebusy_conflict_names,
                                        conflictNames.size,
                                        conflictNames.joinToString("、"),
                                    )
                                }
                                val hint = if (invisibleCount > 0) {
                                    " · " + stringResource(
                                        R.string.freebusy_unresolved_hint, invisibleCount,
                                    )
                                } else ""
                                Text(
                                    text = verdict + hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (conflictNames.isNotEmpty()) {
                                        MaterialTheme.colorScheme.error
                                    } else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = Dimens.SpaceXxs),
                                )
                            }
                            Spacer(Modifier.height(Dimens.SpaceS))
                            Button(
                                onClick = {
                                    val s = selection ?: return@Button
                                    val startSec = day.atStartOfDay(zone)
                                        .plusMinutes(s.startMin.toLong()).toEpochSecond()
                                    val endSec = day.atStartOfDay(zone)
                                        .plusMinutes(s.endMin.toLong()).toEpochSecond()
                                    onCreateEvent(
                                        startSec,
                                        endSec,
                                        checkedPeople.filter { !it.isSelf }
                                            .map { it.userId },
                                    )
                                },
                                enabled = selection != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.freebusy_create))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选择成员页(飞书样式):搜索 + 圆形勾选列表,底部「已选: N 人 + 确定」。
 * 「我」恒选不可取消(发起人必参加)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberPickerPage(
    people: List<PersonColumn>,
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var temp by remember(initial) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val shown = remember(people, query) {
        if (query.isBlank()) people
        else people.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.freebusy_pick_members),
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(shadowElevation = Dimens.ElevationSticky) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                ) {
                    Text(
                        text = stringResource(R.string.freebusy_selected_count, temp.size),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onConfirm(temp) }) {
                        Text(stringResource(R.string.freebusy_confirm))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.freebusy_search_members)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.userId }) { person ->
                    val isChecked = temp.contains(person.userId)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !person.isSelf) {
                                temp = if (isChecked) temp - person.userId
                                else temp + person.userId
                            }
                            .padding(horizontal = Dimens.SpaceXl, vertical = Dimens.SpaceM),
                    ) {
                        Icon(
                            imageVector = if (isChecked) Icons.Filled.CheckCircle
                            else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = when {
                                person.isSelf -> MaterialTheme.colorScheme.primary
                                    .copy(alpha = 0.45f)
                                isChecked -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                            modifier = Modifier.size(Dimens.IconSmall),
                        )
                        Spacer(Modifier.width(Dimens.SpaceM))
                        MemberAvatar(
                            name = person.name,
                            url = person.avatarUrl,
                            cacheKey = "avatar:${person.userId}",
                            size = Dimens.AvatarM,
                        )
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(
                            text = person.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
