package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.data.api.dto.BusyIntervalDto
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
private val MIN_COL_WIDTH = 76.dp

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

    // ── 时段选择(30min 吸附起点,默认 1h)。 ──
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
    LaunchedEffect(day) { selection = null }

    fun busyOverlaps(busy: List<BusyIntervalDto>, sel: TimeSelection): Boolean =
        busy.any { b ->
            val s = runCatching {
                java.time.Duration.between(
                    day.atStartOfDay(zone),
                    OffsetDateTime.parse(b.start).toInstant().atZone(zone),
                ).toMinutes().toInt()
            }.getOrNull() ?: return@any false
            val e = runCatching {
                java.time.Duration.between(
                    day.atStartOfDay(zone),
                    OffsetDateTime.parse(b.end).toInstant().atZone(zone),
                ).toMinutes().toInt()
            }.getOrNull() ?: return@any false
            s < sel.endMin && e > sel.startMin
        }

    val visibleChecked = checkedPeople.filter { busyMap?.containsKey(it.userId) == true }
    val invisibleCount = if (busyMap == null) 0 else checkedPeople.size - visibleChecked.size
    val conflictCount = selection?.let { sel ->
        visibleChecked.count { busyOverlaps(busyMap?.get(it.userId).orEmpty(), sel) }
    } ?: 0

    val hourHeight = 56.dp
    val scrollState = rememberScrollState()
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
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
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
                    .padding(horizontal = 8.dp),
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
                Spacer(Modifier.width(4.dp))
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
                            columns = checkedPeople.map { p ->
                                busyMap?.get(p.userId).orEmpty().mapIndexedNotNull { i, b ->
                                    val startOfDay = day.atStartOfDay(zone)
                                    val s = runCatching {
                                        java.time.Duration.between(
                                            startOfDay,
                                            OffsetDateTime.parse(b.start).toInstant()
                                                .atZone(zone),
                                        ).toMinutes().toInt()
                                    }.getOrNull() ?: return@mapIndexedNotNull null
                                    val e = runCatching {
                                        java.time.Duration.between(
                                            startOfDay,
                                            OffsetDateTime.parse(b.end).toInstant()
                                                .atZone(zone),
                                        ).toMinutes().toInt()
                                    }.getOrNull() ?: return@mapIndexedNotNull null
                                    val cs = s.coerceIn(0, 1440)
                                    val ce = e.coerceIn(0, 1440)
                                    if (ce <= cs) null
                                    else TimeBlock(startMin = cs, endMin = ce, key = "busy-$i")
                                }
                            },
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
                            selectionConflict = conflictCount > 0,
                            onSlotTap = { _, minute ->
                                val start = (minute / 30) * 30
                                selection = TimeSelection(
                                    startMin = start.coerceAtMost(1440 - 30),
                                    endMin = (start + 60).coerceAtMost(1440),
                                )
                            },
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
                                        .padding(vertical = 6.dp),
                                ) {
                                    MemberAvatar(
                                        name = p.name,
                                        url = p.avatarUrl,
                                        cacheKey = "avatar:${p.userId}",
                                        size = 36.dp,
                                    )
                                    Text(
                                        text = p.name,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        color = if (invisible) {
                                            MaterialTheme.colorScheme.outline
                                        } else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    )
                                    if (invisible) {
                                        Text(
                                            text = stringResource(
                                                R.string.freebusy_unavailable,
                                            ),
                                            fontSize = 9.sp,
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
                                            modifier = Modifier.padding(top = 8.dp),
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
                    Surface(shadowElevation = 8.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            val sel = selection
                            if (sel == null) {
                                Text(
                                    stringResource(R.string.freebusy_pick_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "%02d:%02d - %02d:%02d".format(
                                            sel.startMin / 60, sel.startMin % 60,
                                            sel.endMin / 60, sel.endMin % 60,
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            selection = sel.copy(
                                                endMin = (sel.endMin + 30)
                                                    .coerceAtMost(1440),
                                            )
                                        },
                                        enabled = sel.endMin < 1440,
                                        contentPadding = androidx.compose.foundation.layout
                                            .PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(48.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.freebusy_extend),
                                            fontSize = 12.sp,
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedButton(
                                        onClick = {
                                            selection = sel.copy(endMin = sel.endMin - 30)
                                        },
                                        // Disable at the 30-min floor instead of
                                        // a silent no-op tap.
                                        enabled = sel.endMin - sel.startMin > 30,
                                        contentPadding = androidx.compose.foundation.layout
                                            .PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(48.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.freebusy_shrink),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                val verdict = when {
                                    conflictCount > 0 -> stringResource(
                                        R.string.freebusy_conflict, conflictCount,
                                    )
                                    else -> stringResource(R.string.freebusy_all_free)
                                }
                                val hint = if (invisibleCount > 0) {
                                    " · " + stringResource(
                                        R.string.freebusy_unresolved_hint, invisibleCount,
                                    )
                                } else ""
                                Text(
                                    text = verdict + hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (conflictCount > 0) {
                                        MaterialTheme.colorScheme.error
                                    } else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
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
            TopAppBar(
                title = { Text(stringResource(R.string.freebusy_pick_members)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            .padding(horizontal = 20.dp, vertical = 12.dp),
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
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        MemberAvatar(
                            name = person.name,
                            url = person.avatarUrl,
                            cacheKey = "avatar:${person.userId}",
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(12.dp))
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
