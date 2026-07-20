package com.we.meet.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.core.directory.ui.MemberAvatar
import com.we.meet.data.api.dto.BusyIntervalDto
import com.we.meet.ui.calendar.views.TimeSelection
import com.we.meet.ui.calendar.views.TimeBlock
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

/** 忙闲页一列 = 一个人;busy == null 表示该 id 缺席于 freebusy results(跨组织不可见)。 */
private data class PersonColumn(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val isSelf: Boolean,
)

/** 默认勾选渲染的列数上限(可手动增减,防大群首屏 10+ 列过窄)。 */
private const val DEFAULT_CHECKED = 10

/** 单次 freebusy 请求的人数上限(attendee_ids 走 query string)。 */
private const val MAX_IDS = 50

/**
 * P8 忙闲对比页(对标飞书「查看日历/群成员日历」,单聊/群聊共用):
 * 顶部头像行(群可勾选子集,「我」恒选)→ 纵向时间轴一人一列 busy 灰块;
 * 点空白 = 30min 吸附起点的 1 小时选段(±30min 微调),底部确认条给出
 * 「所有参与者都有空 / N 人忙碌」,「创建日程」带 时段+勾选成员 预填跳转。
 *
 * 状态屏内自管(对齐 MemberDetailScreen 模式,不引 VM 工厂):身份拉一次,
 * 忙闲按 (日期, 勾选集) 重拉。
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
                val meDeferred = async { runCatching { app.apiClient.userApi.getMe() }.getOrNull() }
                val me = meDeferred.await()
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
        checked = people.take(DEFAULT_CHECKED).map { it.userId }.toSet()
        identityLoading = false
    }

    // ── 日期 + 勾选 → 忙闲重拉。busyMap 缺 key = 不可见。 ──
    var day by remember { mutableStateOf(LocalDate.now()) }
    var busyMap by remember { mutableStateOf<Map<String, List<BusyIntervalDto>>?>(null) }
    var busyError by remember { mutableStateOf(false) }
    val checkedPeople = remember(people, checked) { people.filter { checked.contains(it.userId) } }
    LaunchedEffect(day, checked, people) {
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
        }.onFailure {
            busyError = true
            busyMap = emptyMap()
        }
    }

    // ── 时段选择(30min 吸附起点,默认 1h)。 ──
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
    LaunchedEffect(day) { selection = null }

    // 选段冲突:仅对「勾选且可见」的列判定;不可见列单独提示。
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            // 日期条:‹ 今天 › + M月d日 周X
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
                    // 头像行:全员横滚;群聊点头像勾选/取消(「我」恒选)。
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 6.dp,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(people, key = { it.userId }) { person ->
                            val isChecked = checked.contains(person.userId)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable(enabled = !person.isSelf) {
                                    checked = if (isChecked) {
                                        checked - person.userId
                                    } else {
                                        checked + person.userId
                                    }
                                },
                            ) {
                                Box(
                                    modifier = if (isChecked) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape,
                                    ) else Modifier,
                                ) {
                                    MemberAvatar(
                                        name = person.name,
                                        url = person.avatarUrl,
                                        cacheKey = "avatar:${person.userId}",
                                        size = 40.dp,
                                    )
                                }
                                Text(
                                    text = person.name,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(56.dp),
                                    color = if (isChecked) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        TimelineScaffold(
                            columns = checkedPeople.map { p ->
                                busyMap?.get(p.userId).orEmpty().mapIndexedNotNull { i, b ->
                                    val startOfDay = day.atStartOfDay(zone)
                                    val s = runCatching {
                                        java.time.Duration.between(
                                            startOfDay,
                                            OffsetDateTime.parse(b.start).toInstant().atZone(zone),
                                        ).toMinutes().toInt()
                                    }.getOrNull() ?: return@mapIndexedNotNull null
                                    val e = runCatching {
                                        java.time.Duration.between(
                                            startOfDay,
                                            OffsetDateTime.parse(b.end).toInstant().atZone(zone),
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
                        )
                        if (busyMap == null && checkedPeople.isNotEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
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
                                                endMin = (sel.endMin + 30).coerceAtMost(1440),
                                            )
                                        },
                                        contentPadding = androidx.compose.foundation.layout
                                            .PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.freebusy_extend),
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedButton(
                                        onClick = {
                                            if (sel.endMin - sel.startMin > 30) {
                                                selection = sel.copy(endMin = sel.endMin - 30)
                                            }
                                        },
                                        contentPadding = androidx.compose.foundation.layout
                                            .PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.freebusy_shrink),
                                            fontSize = 11.sp,
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
                                        checkedPeople.filter { !it.isSelf }.map { it.userId },
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
