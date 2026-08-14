package com.we.meet.ui.calendar.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.we.meet.ui.calendar.EventUi
import com.we.meet.ui.calendar.RsvpVisual
import com.we.meet.ui.calendar.rsvpAccentColor
import com.we.meet.ui.calendar.parseCalendarColor
import com.we.meet.ui.calendar.rsvpBlockBackground
import com.we.meet.ui.calendar.rsvpTextColor
import com.we.meet.ui.calendar.rsvpVisualOf
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTextStyles
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * P8 纵向时间轴的共用积木:日视图(1 列)/三日视图(3 列)/忙闲对比页(一人一列)
 * 共享同一份实现 —— Canvas 画格线 + offset 摆块 + verticalScroll,整格
 * pointerInput 命中(块 → onBlockTap,空白 → onSlotTap)。
 *
 * P8-UX 修正(对齐飞书):传 [minColumnWidth] 时列宽弹性但有下限,列多时
 * **列头 + 网格整体横向滚动**(共享同一 hScroll);列头由 [columnHeader]
 * 渲染在网格上方并与列严格对齐。不传时保持等分布局(日/三日视图不变)。
 */

/** 一个已裁剪到当日 [0,1440) 分钟制的渲染块(日程或忙碌区间通用)。 */
data class TimeBlock(
    val startMin: Int,
    val endMin: Int,
    /** 块内标题;忙闲块传 null(纯色块,不泄露标题)。 */
    val label: String? = null,
    /** 「HH:mm – HH:mm」时间行(对齐 Web:长块第二行,短块并入标题行)。 */
    val timeLabel: String? = null,
    /** eventId / "busy-i" —— onBlockTap 回传。 */
    val key: String,
    /** 取消的日程等:半透明 + 删除线。 */
    val faded: Boolean = false,
    /** P8「降低已结束日程的亮度」:整块降透明度,不加删除线。 */
    val dimmed: Boolean = false,
    /** 我的表态(`my_rsvp`);忙闲块传 null。见 [rsvpVisualOf]。 */
    val rsvp: String? = null,
    /** Calendar projection color; null for free/busy blocks. */
    val calendarColor: String? = null,
    /**
     * 允许长按拖动改期(整块移位)。仅「我组织的、非重复、当日内起止」的日程
     * 为 true —— 重复日程涉及三选语义,忙闲/他人日程无权改。
     */
    val movable: Boolean = false,
    /**
     * 斜纹 + 虚线框(飞书同款「还没定下来」的观感)。忙闲页给「对方尚未回复
     * 我这场会」的块用 —— 这类冲突是软的,与已接受的实心块要能一眼分开。
     * 日/三日视图不传:那里未回复走的是四色里的紫,不改既有观感。
     */
    val hatched: Boolean = false,
)

/** 短块阈值(分钟):≤45 分钟块高只够一行,时间并入标题行(对齐 Web)。 */
internal const val SHORT_BLOCK_MIN = 45

/** 无标题的忙闲块:≥这么长才写得下时段文字(更短的靠点击提示)。 */
private const val BUSY_TIME_LABEL_MIN = 30

private fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

internal fun hourRailLabelMinutes(
    visibleStartMin: Int,
    visibleEndMin: Int,
): List<Int> {
    val firstHour = ((visibleStartMin + 59) / 60) * 60
    return buildList {
        add(visibleStartMin)
        var minute = firstHour
        while (minute < visibleEndMin) {
            add(minute)
            minute += 60
        }
        add(visibleEndMin)
    }.distinct()
}

/** Place the measured text box with its vertical center on the modifier's y offset. */
private fun Modifier.centerOnAnchorY(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, -placeable.height / 2)
    }
}

/** 忙闲页选中的时段(横贯所有列的高亮框)。 */
data class TimeSelection(val startMin: Int, val endMin: Int)

/**
 * 日/三日视图的「预选时段」草稿(对齐飞书):点空白先落一个预选块,拖上下边界
 * 手柄改起止,**再次点这个块**才进创建日程表单。[colIndex] = 所在列(日视图恒 0)。
 */
data class DraftSelection(val colIndex: Int, val startMin: Int, val endMin: Int)

/**
 * 屏级的预选时段(带日期):日视图/三日视图共用同一份状态,列索引在各视图内部
 * 换算。起止为当日分钟制 [0,1440]。
 */
data class DraftSlot(val date: LocalDate, val startMin: Int, val endMin: Int)

internal fun timelineNeedsHorizontalScroll(
    columnCount: Int,
    visibleColumnCount: Int?,
    minColumnWidthExceeded: Boolean,
): Boolean =
    (visibleColumnCount != null && visibleColumnCount < columnCount) ||
        minColumnWidthExceeded

/**
 * Measures a wide child at [contentWidthPx], reports only the bounded viewport width, and places
 * the child at [offsetPx]. This keeps buffered calendar columns aligned without a ScrollState.
 */
private fun Modifier.fixedHorizontalViewport(
    contentWidthPx: Int,
    offsetPx: () -> Float,
): Modifier = layout { measurable, constraints ->
    val measuredWidth = contentWidthPx.coerceAtLeast(constraints.minWidth)
    val placeable = measurable.measure(
        constraints.copy(minWidth = measuredWidth, maxWidth = measuredWidth),
    )
    val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else measuredWidth
    layout(viewportWidth, placeable.height) {
        placeable.placeRelative(-offsetPx().roundToInt(), 0)
    }
}

/** 预选块拖拽的吸附粒度(分钟) —— 与飞书一致取 15。 */
const val DRAFT_SNAP_MIN = 15

/** 预选块的最短时长(分钟):拖到重合时留一格,块仍可见可点。 */
private const val DRAFT_MIN_DURATION = DRAFT_SNAP_MIN

/** 手柄触摸半径 / 距列边的水平内缩(手柄画在边界线上,上右下左各一个)。 */
private val DRAFT_HANDLE_TOUCH = Dimens.Calendar.DraftHandleTouch
private val DRAFT_HANDLE_INSET = Dimens.Calendar.DraftHandleInset
private val DRAFT_HANDLE_SIZE = Dimens.Calendar.DraftHandleSize

/** 拖动中的整块移位预览(日程块长按拖动时的落点;预选块直接改 draft)。 */
private data class MovePreview(
    val key: String,
    val colIndex: Int,
    val startMin: Int,
    val endMin: Int,
)

/** 把像素 y 折算成吸附到 [DRAFT_SNAP_MIN] 的分钟数(钳进当日)。 */
private fun snapMinuteAt(
    y: Float,
    hourHeightPx: Float,
    visibleStartMin: Int,
    visibleEndMin: Int,
): Int {
    val raw = visibleStartMin + y / hourHeightPx * 60f
    return ((raw / DRAFT_SNAP_MIN).roundToInt() * DRAFT_SNAP_MIN)
        .coerceIn(visibleStartMin, visibleEndMin)
}

/**
 * 在 [date] 的 [minuteOfDay] 处落一个 [durationMin] 长的预选块:起点向下吸附到
 * 30 分钟格,越过当日末尾则整体前移(保时长)。日/三日视图点空白时共用。
 */
fun draftSlotAt(date: LocalDate, minuteOfDay: Int, durationMin: Int): DraftSlot {
    val dur = durationMin.coerceIn(DRAFT_MIN_DURATION, 24 * 60)
    val snapped = (minuteOfDay / 30) * 30
    val start = snapped.coerceIn(0, 24 * 60 - dur)
    return DraftSlot(date, start, start + dur)
}

fun draftSlotAt(
    date: LocalDate,
    minuteOfDay: Int,
    durationMin: Int,
    visibleStartMin: Int,
    visibleEndMin: Int,
): DraftSlot {
    val startBound = visibleStartMin.coerceIn(0, 24 * 60 - DRAFT_MIN_DURATION)
    val endBound = visibleEndMin.coerceIn(startBound + DRAFT_MIN_DURATION, 24 * 60)
    val duration = durationMin.coerceIn(DRAFT_MIN_DURATION, endBound - startBound)
    val snapped = (minuteOfDay / DRAFT_SNAP_MIN) * DRAFT_SNAP_MIN
    val start = snapped.coerceIn(startBound, endBound - DRAFT_MIN_DURATION)
    val end = (start + duration).coerceAtMost(endBound)
    return DraftSlot(date, start, end)
}

/**
 * 把日程投影成 [date] 当日的时间块;全天/不覆盖当日 → null。
 * [dimPastNow] 非空时,结束时刻早于它的块标记 dimmed(P8 日历设置)。
 * [selfUserId] 非空且我就是组织者时,单日内的非重复日程可长按拖动改期。
 */
fun EventUi.toTimeBlockOrNull(
    date: LocalDate,
    dimPastNow: java.time.ZonedDateTime? = null,
    selfUserId: String? = null,
): TimeBlock? {
    if (allDay) return null
    val startDate = start.toLocalDate()
    val endDate = end.toLocalDate()
    if (startDate > date || endDate < date) return null
    val s = if (startDate < date) 0 else start.hour * 60 + start.minute
    val e = when {
        endDate > date -> 24 * 60
        else -> end.hour * 60 + end.minute
    }
    if (e <= s) return null // 例如恰好在当日 00:00 结束(属于前一日)
    return TimeBlock(
        startMin = s,
        endMin = e,
        label = title,
        timeLabel = "${fmtMin(s)} – ${fmtMin(e)}",
        key = id,
        faded = cancelled,
        dimmed = dimPastNow != null && end.isBefore(dimPastNow),
        rsvp = myRsvp,
        calendarColor = calendarColor,
        // 跨天的块被裁过(s/e 不是真实起止),拖动会把另一半算错 → 不开放。
        movable = canEdit &&
            !recurring && !cancelled &&
            startDate == date && endDate == date,
    )
}

/**
 * 左侧小时刻度列(00:00–23:00)。[highlightMinutes] 非空时在对应位置叠一层主色
 * 时刻(预选块的起止),带底色遮住重叠的整点刻度 —— 对齐飞书。
 */
@Composable
fun HourRail(
    hourHeight: Dp,
    modifier: Modifier = Modifier,
    highlightMinutes: List<Int> = emptyList(),
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
) {
    val rangeMinutes = (visibleEndMin - visibleStartMin).coerceAtLeast(DRAFT_MIN_DURATION)
    val labels = hourRailLabelMinutes(visibleStartMin, visibleEndMin)
    Box(
        modifier = modifier
            .width(HOUR_RAIL_WIDTH)
            .height(hourHeight * (rangeMinutes / 60f)),
    ) {
        labels.forEach { min ->
            Text(
                text = fmtMin(min),
                style = WeMeetTextStyles.LabelTiny,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(y = hourHeight * ((min - visibleStartMin) / 60f))
                    .fillMaxWidth()
                    .centerOnAnchorY(),
            )
        }
        highlightMinutes
            .filter { it in visibleStartMin..visibleEndMin }
            .forEach { min ->
                Text(
                    text = fmtMin(min),
                    style = WeMeetTextStyles.LabelTiny,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(
                            y = hourHeight * ((min - visibleStartMin) / 60f),
                        )
                        .fillMaxWidth()
                        .centerOnAnchorY()
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
    }
}

val HOUR_RAIL_WIDTH = Dimens.Calendar.HourRailWidth

/**
 * 时间轴主体:[columns] 列 + 24h 格线 + 工作时间外淡阴影 + 当前时刻红线 +
 * 选中时段高亮。[disabledColumn] 为 true 的列整体置灰(忙闲不可见)。
 *
 * 列宽:缺省等分;[minColumnWidth] 非空时 = max(下限, 可用宽/n),超出视口
 * 则(连同 [columnHeader] 列头)整体横向滚动。
 */
@Composable
fun TimelineScaffold(
    columns: List<List<TimeBlock>>,
    modifier: Modifier = Modifier,
    hourHeight: Dp = Dimens.Calendar.HourHeight,
    scrollState: ScrollState = rememberScrollState(),
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
    workingStartMin: Int = 9 * 60,
    workingEndMin: Int = 18 * 60,
    nowMinute: Int? = null,
    nowLineInColumn: (Int) -> Boolean = { true },
    disabledColumn: (Int) -> Boolean = { false },
    selection: TimeSelection? = null,
    selectionConflict: Boolean = false,
    /**
     * 忙闲页选段可拖:拖上下圆抓手改起止、拖框本体整体移位(保时长)。抓手
     * 恒贴视口左右缘 —— 选段横贯所有列,人多横滚时贴内容边缘会滚出视野。
     */
    onSelectionAdjust: ((TimeSelection) -> Unit)? = null,
    onSlotTap: ((colIndex: Int, minuteOfDay: Int) -> Unit)? = null,
    onBlockTap: ((colIndex: Int, key: String) -> Unit)? = null,
    minColumnWidth: Dp? = null,
    /** Fixed content above the hour rail, such as the device timezone label. */
    railHeader: (@Composable () -> Unit)? = null,
    columnHeader: (@Composable (colIndex: Int) -> Unit)? = null,
    /** 窄列(三日视图等多列布局)块内只显标题,不显时间 —— 时刻由纵向位置 +
     *  左侧刻度传达;日视图单宽列仍标题 + 时间。 */
    compactBlocks: Boolean = false,
    /** 非空时一屏恰好铺 [visibleColumnCount] 列。只有实际列数更多时，列头与
     *  网格才挂载横向滚动节点；列数未超出时保持纯固定宽度布局。 */
    visibleColumnCount: Int? = null,
    /**
     * Optional pixel offset into [columns] for externally driven paging. When set, the header
     * and grid are clipped and translated together without exposing free horizontal scrolling.
     */
    horizontalContentOffsetPx: (() -> Float)? = null,
    /** 非空时初次布局横滚到让该列可见。 */
    revealColumnIndex: Int? = null,
    /** 预选时段草稿(点空白后出现);为空 = 无草稿。见 [DraftSelection]。 */
    draft: DraftSelection? = null,
    draftConflict: Boolean = false,
    /** 预选块里显示的文案(通常「添加日程」);null 时不画草稿。 */
    draftLabel: String? = null,
    /** 拖动上/下手柄时持续回调新草稿(已吸附到 [DRAFT_SNAP_MIN])。 */
    onDraftAdjust: ((DraftSelection) -> Unit)? = null,
    /** 再次点击预选块 → 确认(调用方据此进创建表单)。 */
    onDraftConfirm: ((DraftSelection) -> Unit)? = null,
    /**
     * 日程块改期落点(松手时回调,仅 [TimeBlock.movable] 的块参与):整块移位
     * 保时长、拖抓手只改一头,跨列 = 改日期(多日视图)。两条路径同一个回调 ——
     * 语义都是「这块的新起止」。
     */
    onBlockMove: (
        (colIndex: Int, key: String, startMin: Int, endMin: Int) -> Unit
    )? = null,
    /**
     * 当前处于选中态的日程块 key(长按选中):画主色描边 + 上下圆抓手,可直接
     * 整块拖移、可拖抓手改时长。为空 = 无选中。
     */
    selectedBlockKey: String? = null,
    /**
     * 长按可改期的日程块 → 进入选中态。手指不抬继续滑动就直接进整块拖动
     * (保留老的「长按拖动」一气呵成),抬手则停在选中态等下一步操作。
     */
    onBlockSelect: ((key: String) -> Unit)? = null,
    /**
     * 点左侧时刻刻度列 —— 那儿既不落预选框也不选日程,和点网格外一样算
     * 「点在当前操作对象以外」,调用方据此收手。
     */
    onRailTap: (() -> Unit)? = null,
) {
    val n = columns.size.coerceAtLeast(1)
    val rangeStart = visibleStartMin.coerceIn(0, 24 * 60 - DRAFT_MIN_DURATION)
    val rangeEnd = visibleEndMin.coerceIn(rangeStart + DRAFT_MIN_DURATION, 24 * 60)
    val rangeMinutes = rangeEnd - rangeStart
    val nowLineColor = WeMeetTheme.extras.calendar.nowLine
    val selectionConflictColor = WeMeetTheme.extras.calendar.conflict
    val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val offWorkShade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
    val busyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // 表态四态四色(见 RsvpVisuals):竖条 = 强调色、块底 = 同色低透明、
    // 文字 = 同色深档;忙闲块(label == null)不参与,仍用中性 busyColor。
    val accentOf = RsvpVisual.entries.associateWith { rsvpAccentColor(it) }
    val textOf = RsvpVisual.entries.associateWith { rsvpTextColor(it) }
    val bgOf = RsvpVisual.entries.associateWith { rsvpBlockBackground(it) }
    // 短块「标题,时间」分隔符:中文全角逗号(对齐 Web),其他语言半角。
    val titleTimeSep = if (Locale.getDefault().language == "zh") "，" else ", "
    val density = LocalDensity.current
    val hourLabelTopInset = with(density) {
        WeMeetTextStyles.LabelTiny.lineHeight.toDp() / 2
    }
    // 内容超出视口时，列头与网格共享一个横向 ScrollState → 严格同步滚动。
    val hScroll = rememberScrollState()
    // 草稿相关的最新值用 rememberUpdatedState 兜住:pointerInput 的 key 不能带
    // draft —— 拖动中每帧都会更新它,重启 key 会把正在进行的手势掐断。
    val draftNow = rememberUpdatedState(draft)
    val adjustNow = rememberUpdatedState(onDraftAdjust)
    val confirmNow = rememberUpdatedState(onDraftConfirm)
    val columnsNow = rememberUpdatedState(columns)
    val moveNow = rememberUpdatedState(onBlockMove)
    val selectedNow = rememberUpdatedState(selectedBlockKey)
    val selectNow = rememberUpdatedState(onBlockSelect)
    val selectionNow = rememberUpdatedState(selection)
    val selAdjustNow = rememberUpdatedState(onSelectionAdjust)
    val railTapNow = rememberUpdatedState(onRailTap)
    val handleTouchPx = with(density) { DRAFT_HANDLE_TOUCH.toPx() }
    // 日程块长按拖动中的落点预览(原位留虚影);null = 没在拖。
    var movePreview by remember { mutableStateOf<MovePreview?>(null) }
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier = modifier) {
        val available = maxWidth - HOUR_RAIL_WIDTH
        val equalSplit = available / n
        val colWidth: Dp = when {
            // 定量可见列数优先:列多于一屏时按 available/可见列数定宽 → 横滚。
            visibleColumnCount != null && visibleColumnCount < n ->
                available / visibleColumnCount
            minColumnWidth != null && equalSplit < minColumnWidth -> minColumnWidth
            else -> equalSplit
        }
        val contentWidth = colWidth * n
        val horizontallyScrollable = timelineNeedsHorizontalScroll(
            columnCount = n,
            visibleColumnCount = visibleColumnCount,
            minColumnWidthExceeded = minColumnWidth != null && equalSplit < minColumnWidth,
        )
        val usesNativeHorizontalScroll =
            horizontallyScrollable && horizontalContentOffsetPx == null
        val hourHeightPx = with(density) { hourHeight.toPx() }
        val colWidthPx = with(density) { colWidth.toPx() }
        // 网格视口宽(不含左侧刻度列):忙闲页选段的抓手贴它的左右缘定位。
        val availablePx = with(density) { available.toPx() }
        fun minuteY(minute: Int): Dp = hourHeight * ((minute - rangeStart) / 60f)
        fun minuteYPx(minute: Int): Float =
            (minute - rangeStart) / 60f * hourHeightPx

        // 初次布局(及列宽变化时)横滚到让 revealColumnIndex 列落在可见区内:
        // 若它在前 vc 列内则回到最左,否则滚到把它作为最右可见列。
        if (usesNativeHorizontalScroll && revealColumnIndex != null) {
            LaunchedEffect(revealColumnIndex, n, colWidthPx, visibleColumnCount) {
                val vc = visibleColumnCount ?: n
                val target = (revealColumnIndex - (vc - 1)).coerceAtLeast(0) * colWidthPx
                hScroll.scrollTo(target.toInt())
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (railHeader != null || columnHeader != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier
                            .width(HOUR_RAIL_WIDTH)
                            .align(Alignment.Bottom),
                    ) {
                        railHeader?.invoke()
                    }
                    if (columnHeader != null) {
                        if (horizontalContentOffsetPx != null) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clipToBounds()
                                    .fixedHorizontalViewport(
                                        contentWidthPx = (colWidthPx * n).roundToInt(),
                                        offsetPx = horizontalContentOffsetPx,
                                    ),
                            ) {
                                for (i in 0 until n) {
                                    Box(modifier = Modifier.width(colWidth)) { columnHeader(i) }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (usesNativeHorizontalScroll) {
                                            Modifier.horizontalScroll(hScroll)
                                        } else Modifier,
                                    ),
                            ) {
                                for (i in 0 until n) {
                                    Box(modifier = Modifier.width(colWidth)) { columnHeader(i) }
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(vertical = hourLabelTopInset),
            ) {
                // 左侧刻度高亮:预选框 > 拖动中的落点 > 选中态日程块的起止
                // —— 都是「用户此刻正在摆弄的时段」,读时刻靠它。
                val railHighlights = draft?.let { listOf(it.startMin, it.endMin) }
                    ?: movePreview?.let { listOf(it.startMin, it.endMin) }
                    ?: selection?.let { listOf(it.startMin, it.endMin) }
                    ?: selectedBlockKey?.let { key ->
                        columns.firstNotNullOfOrNull { list ->
                            list.firstOrNull { it.key == key && it.movable }
                        }?.let { listOf(it.startMin, it.endMin) }
                    }
                    ?: emptyList()
                HourRail(
                    hourHeight,
                    modifier = if (onRailTap != null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { railTapNow.value?.invoke() }
                        }
                    } else Modifier,
                    highlightMinutes = railHighlights,
                    visibleStartMin = rangeStart,
                    visibleEndMin = rangeEnd,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                        .then(
                            if (usesNativeHorizontalScroll) Modifier.horizontalScroll(hScroll)
                            else Modifier,
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .then(
                                if (horizontalContentOffsetPx != null) {
                                    Modifier.fixedHorizontalViewport(
                                        contentWidthPx = (colWidthPx * n).roundToInt(),
                                        offsetPx = horizontalContentOffsetPx,
                                    )
                                } else Modifier.width(contentWidth),
                            )
                            .height(hourHeight * (rangeMinutes / 60f))
                            // 拖拽四条路径,统一在这里命中派发:
                            // ① 预选块的上下手柄 = 改起止;② 预选块本体 = 整块移位;
                            // ③ 选中态日程块的上下抓手 = 改时长;④ 日程块本体 =
                            //    已选中则直接拖、未选中则长按先进选中态(不抬手可续拖)。
                            // 一旦接管就在 Initial 阶段吃掉事件,外层 verticalScroll /
                            // horizontalScroll 收不到,拖动不会退化成滚动;没接管则原样
                            // 放行(照常滚动 / 交给下面的点击处理)。
                            .pointerInput(hourHeightPx, colWidthPx, handleTouchPx, n, availablePx) {
                                val insetPx = DRAFT_HANDLE_INSET.toPx()
                                val slopPx = viewConfiguration.touchSlop
                                val longPressMs = viewConfiguration.longPressTimeoutMillis
                                fun colAt(x: Float) =
                                    (x / colWidthPx).toInt().coerceIn(0, n - 1)

                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    val d = draftNow.value
                                    val adjust = adjustNow.value
                                    val downCol = colAt(down.position.x)
                                    val downMin = (rangeStart +
                                        (down.position.y / hourHeightPx) * 60)
                                        .toInt()
                                        .coerceIn(rangeStart, rangeEnd - 1)

                                    // ① 手柄:落点离哪个手柄近就拖哪头,立即接管。
                                    if (d != null && adjust != null) {
                                        val baseX = colWidthPx * d.colIndex
                                        val topAt = Offset(
                                            baseX + colWidthPx - insetPx,
                                            minuteYPx(d.startMin),
                                        )
                                        val botAt = Offset(
                                            baseX + insetPx,
                                            minuteYPx(d.endMin),
                                        )
                                        val dTop = (down.position - topAt).getDistance()
                                        val dBot = (down.position - botAt).getDistance()
                                        if (dTop <= handleTouchPx || dBot <= handleTouchPx) {
                                            // 短块时两手柄挨得近,取更近的那个。
                                            down.consume()
                                            dragEdge(
                                                down.id,
                                                hourHeightPx,
                                                d.startMin,
                                                d.endMin,
                                                movingStart = dTop <= dBot,
                                                visibleStartMin = rangeStart,
                                                visibleEndMin = rangeEnd,
                                            ) { s, e ->
                                                adjust(d.copy(startMin = s, endMin = e))
                                            }
                                            return@awaitEachGesture
                                        }
                                    }

                                    // ①' 忙闲页选段(横贯所有列):抓手改起止 / 框内
                                    // 过 slop 整块移位。抓手按视口边缘定位,与渲染一致。
                                    val sel = selectionNow.value
                                    val selAdjust = selAdjustNow.value
                                    if (sel != null && selAdjust != null) {
                                        val viewportLeft = horizontalContentOffsetPx?.invoke()
                                            ?: hScroll.value.toFloat()
                                        val selTopY = minuteYPx(sel.startMin)
                                        val selBotY = minuteYPx(sel.endMin)
                                        val topAt = Offset(
                                            viewportLeft + availablePx - insetPx, selTopY,
                                        )
                                        val botAt = Offset(viewportLeft + insetPx, selBotY)
                                        val dTop = (down.position - topAt).getDistance()
                                        val dBot = (down.position - botAt).getDistance()
                                        if (dTop <= handleTouchPx || dBot <= handleTouchPx) {
                                            down.consume()
                                            dragEdge(
                                                down.id,
                                                hourHeightPx,
                                                sel.startMin,
                                                sel.endMin,
                                                movingStart = dTop <= dBot,
                                                visibleStartMin = rangeStart,
                                                visibleEndMin = rangeEnd,
                                            ) { s, e -> selAdjust(TimeSelection(s, e)) }
                                            return@awaitEachGesture
                                        }
                                        if (downMin >= sel.startMin && downMin < sel.endMin) {
                                            // 框内:过 slop 才算移位(不到 slop 松手 = 点击,
                                            // 交给 onSlotTap 按老语义重选)。
                                            if (!awaitSlopExceeded(
                                                    down.id, down.position, slopPx,
                                                )
                                            ) {
                                                return@awaitEachGesture
                                            }
                                            val duration = sel.endMin - sel.startMin
                                            val grab = downMin - sel.startMin
                                            while (true) {
                                                val ev =
                                                    awaitPointerEvent(PointerEventPass.Initial)
                                                val ch = ev.changes
                                                    .firstOrNull { it.id == down.id } ?: break
                                                ch.consume()
                                                if (!ch.pressed) break
                                                val head = rangeStart +
                                                    (ch.position.y / hourHeightPx * 60f) - grab
                                                val s = ((head / DRAFT_SNAP_MIN).roundToInt() *
                                                    DRAFT_SNAP_MIN)
                                                    .coerceIn(rangeStart, rangeEnd - duration)
                                                selAdjust(TimeSelection(s, s + duration))
                                            }
                                            return@awaitEachGesture
                                        }
                                    }

                                    // ② 预选块本体:超过触摸 slop 才接管(不到 slop 松手
                                    // = 点击确认,交给下面的 tap 处理)。
                                    val onDraftBody = d != null && adjust != null &&
                                        downCol == d.colIndex &&
                                        downMin >= d.startMin && downMin < d.endMin
                                    val moveCb = moveNow.value

                                    // ③ 选中态日程块的上下抓手:改时长(只动一头,时长
                                    // 最短一格)。抓手骑在边界线上,按坐标距离命中 ——
                                    // 落点可能在块外一点,不能用 minute 落在块内来判。
                                    val selKey = selectedNow.value
                                    if (!onDraftBody && selKey != null && moveCb != null) {
                                        var selCol = -1
                                        var selBlock: TimeBlock? = null
                                        columnsNow.value.forEachIndexed { ci, list ->
                                            list.firstOrNull { it.key == selKey }?.let {
                                                selCol = ci
                                                selBlock = it
                                            }
                                        }
                                        val sb = selBlock
                                        if (sb != null && sb.movable) {
                                            val baseX = colWidthPx * selCol
                                            val topAt = Offset(
                                                baseX + colWidthPx - insetPx,
                                                minuteYPx(sb.startMin),
                                            )
                                            val botAt = Offset(
                                                baseX + insetPx,
                                                minuteYPx(sb.endMin),
                                            )
                                            val dTop = (down.position - topAt).getDistance()
                                            val dBot = (down.position - botAt).getDistance()
                                            if (dTop <= handleTouchPx || dBot <= handleTouchPx) {
                                                down.consume()
                                                val (start, end) = dragEdge(
                                                    down.id,
                                                    hourHeightPx,
                                                    sb.startMin,
                                                    sb.endMin,
                                                    movingStart = dTop <= dBot,
                                                    visibleStartMin = rangeStart,
                                                    visibleEndMin = rangeEnd,
                                                ) { s, e ->
                                                    movePreview =
                                                        MovePreview(selKey, selCol, s, e)
                                                }
                                                movePreview = null
                                                if (start != sb.startMin || end != sb.endMin) {
                                                    moveCb(selCol, selKey, start, end)
                                                }
                                                return@awaitEachGesture
                                            }
                                        }
                                    }

                                    // ④ 日程块本体:已选中 → 直接拖(用户已表态,独占手势);
                                    // 未选中 → 长按先进选中态,免得滚动时误拖。
                                    val block = if (onDraftBody || moveCb == null) null
                                    else columnsNow.value.getOrNull(downCol)
                                        ?.firstOrNull {
                                            it.movable &&
                                                downMin >= it.startMin && downMin < it.endMin
                                        }
                                    if (!onDraftBody && block == null) return@awaitEachGesture

                                    val takeOver = when {
                                        onDraftBody ->
                                            awaitSlopExceeded(down.id, down.position, slopPx)
                                        block!!.key == selKey ->
                                            awaitSlopExceeded(down.id, down.position, slopPx)
                                        !awaitLongPress(
                                            down.id, down.position, slopPx, longPressMs,
                                        ) -> false
                                        else -> {
                                            // 长按成立:先给选中态 + 震一下;手指不抬继续
                                            // 滑就直接进拖动(老的「长按拖动」一气呵成),
                                            // 抬手则停在选中态。抬手事件要消费掉,否则会
                                            // 被下面的 tap 当成点击弹详情。
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                            selectNow.value?.invoke(block.key)
                                            awaitDragAfterSelect(
                                                down.id, down.position, slopPx,
                                            )
                                        }
                                    }
                                    if (!takeOver) return@awaitEachGesture

                                    // 整块移位:保时长,起点跟手(按 15 分钟吸附),
                                    // 横向跨列 = 改日期(多日视图)。
                                    val origStart =
                                        if (onDraftBody) d!!.startMin else block!!.startMin
                                    val origEnd =
                                        if (onDraftBody) d!!.endMin else block!!.endMin
                                    val duration = origEnd - origStart
                                    val grabMin = downMin - origStart
                                    var lastCol = if (onDraftBody) d!!.colIndex else downCol
                                    var lastStart = origStart
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        ch.consume()
                                        if (!ch.pressed) break
                                        val head = rangeStart +
                                            (ch.position.y / hourHeightPx * 60f) - grabMin
                                        lastStart = ((head / DRAFT_SNAP_MIN).roundToInt() *
                                            DRAFT_SNAP_MIN)
                                            .coerceIn(rangeStart, rangeEnd - duration)
                                        lastCol = colAt(ch.position.x)
                                        if (onDraftBody) {
                                            adjust!!(
                                                DraftSelection(
                                                    lastCol,
                                                    lastStart,
                                                    lastStart + duration,
                                                ),
                                            )
                                        } else {
                                            movePreview = MovePreview(
                                                block!!.key,
                                                lastCol,
                                                lastStart,
                                                lastStart + duration,
                                            )
                                        }
                                    }
                                    if (block != null) {
                                        movePreview = null
                                        // 原地放下不打扰服务端。
                                        if (lastStart != origStart || lastCol != downCol) {
                                            moveCb!!(
                                                lastCol,
                                                block.key,
                                                lastStart,
                                                lastStart + duration,
                                            )
                                        }
                                    }
                                }
                            },
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(n, hourHeightPx, colWidthPx, columns) {
                                    detectTapGestures { off ->
                                        val col =
                                            (off.x / colWidthPx).toInt().coerceIn(0, n - 1)
                                        val minute = (rangeStart +
                                            (off.y / hourHeightPx) * 60)
                                            .toInt()
                                            .coerceIn(rangeStart, rangeEnd - 1)
                                        // 预选块画在最上层,点它 = 确认建日程(优先于
                                        // 底下压着的日程块)。
                                        val d = draftNow.value
                                        val confirm = confirmNow.value
                                        if (d != null && confirm != null &&
                                            col == d.colIndex &&
                                            minute >= d.startMin && minute < d.endMin
                                        ) {
                                            confirm(d)
                                            return@detectTapGestures
                                        }
                                        val hit = columns[col].firstOrNull {
                                            minute >= it.startMin && minute < it.endMin
                                        }
                                        if (hit != null && onBlockTap != null) {
                                            onBlockTap(col, hit.key)
                                        } else {
                                            onSlotTap?.invoke(col, minute)
                                        }
                                    }
                                },
                        ) {
                            // 工作时间(09–18)以外整行淡阴影。
                            fun shade(from: Int, to: Int) {
                                val start = maxOf(from, rangeStart)
                                val end = minOf(to, rangeEnd)
                                if (end <= start) return
                                drawRect(
                                    offWorkShade,
                                    topLeft = Offset(0f, minuteYPx(start)),
                                    size = Size(
                                        size.width,
                                        (end - start) / 60f * hourHeightPx,
                                    ),
                                )
                            }
                            shade(rangeStart, workingStartMin)
                            shade(workingEndMin, rangeEnd)
                            drawLine(
                                gridLine,
                                Offset.Zero,
                                Offset(size.width, 0f),
                                strokeWidth = 1f,
                            )
                            var gridMinute = ((rangeStart + 59) / 60) * 60
                            while (gridMinute <= rangeEnd) {
                                val y = minuteYPx(gridMinute)
                                drawLine(
                                    gridLine,
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                                gridMinute += 60
                            }
                            for (c in 1 until n) {
                                val x = c * colWidthPx
                                drawLine(
                                    gridLine,
                                    Offset(x, 0f),
                                    Offset(x, size.height),
                                    strokeWidth = 1f,
                                )
                            }
                        }

                        columns.forEachIndexed { i, blocks ->
                            if (disabledColumn(i)) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i)
                                        .width(colWidth)
                                        .fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = 0.06f),
                                        ),
                                )
                            }
                            blocks.filter { it.startMin < rangeEnd && it.endMin > rangeStart }
                                .forEach { b ->
                                val clippedStart = maxOf(b.startMin, rangeStart)
                                val clippedEnd = minOf(b.endMin, rangeEnd)
                                val top = minuteY(clippedStart)
                                val blockHeight =
                                    (hourHeight * ((clippedEnd - clippedStart) / 60f))
                                        .coerceAtLeast(Dimens.Calendar.BlockMinHeight)
                                // 对齐 Web/飞书:左侧实心竖条 + 同色系浅底;短块
                                // (≤45min)时间并入标题行,长块标题行+时间行。
                                // 竖条/底/字的颜色按表态四档取(拒绝档额外删除线)。
                                val visual = rsvpVisualOf(b.rsvp)
                                val declined = visual == RsvpVisual.DECLINED
                                val blockBg = bgOf.getValue(visual)
                                val calendarAccent =
                                    parseCalendarColor(b.calendarColor) ?: accentOf.getValue(visual)
                                // 正在被拖走的块:原位留一层虚影,落点画预览。
                                val ghost = movePreview?.key == b.key
                                /**
                                 * P8「降低已结束日程的亮度」—— 只压**填充**,不压文字。
                                 *
                                 * 原先是整块 `.alpha(0.5f)`,底和字一起淡下去,标题对比度
                                 * 掉到 2.2:1(浅色)/ 2.8:1(深色),两套主题都读不清。
                                 * 「已结束」不等于「不用读」:开完的会叫什么名字,仍然是
                                 * 用户要在网格上一眼扫到的信息。
                                 *
                                 * 改成只把填充压淡后,块整体退到背景里,而标题保持原色 ——
                                 * 底离文字更远了,对比度反而升到 6.8:1 / 8.0:1。
                                 * 强调色条不跟着压:它是表态状态的唯一载体,而且只有 3dp
                                 * 宽,占不了视觉重量。
                                 */
                                val fillDim = if (b.dimmed) 0.5f else 1f
                                // 长按选中态:主色描边提示「这块现在可拖」。
                                val picked = b.key == selectedBlockKey && b.movable
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i, y = top)
                                        .width(colWidth)
                                        .height(blockHeight)
                                        .padding(horizontal = Dimens.Calendar.ChipInset, vertical = Dimens.BorderThin)
                                        // 拖走中的原位虚影:整块降透明,它本来就是个占位。
                                        .alpha(if (ghost) 0.3f else 1f)
                                        .clip(RoundedCornerShape(Dimens.CornerXs))
                                        .background(
                                            color = when {
                                                b.label == null -> busyColor.copy(
                                                    alpha = busyColor.alpha * fillDim,
                                                )
                                                b.hatched -> blockBg.copy(alpha = 0.35f * fillDim)
                                                b.faded -> blockBg.copy(alpha = 0.45f * fillDim)
                                                else -> blockBg.copy(
                                                    alpha = blockBg.alpha * fillDim,
                                                )
                                            },
                                        )
                                        // 未回复:斜纹 + 虚线框(飞书同款「还没定」)。
                                        .then(
                                            if (b.hatched) {
                                                Modifier.hatchedOutline(
                                                    calendarAccent,
                                                )
                                            } else Modifier,
                                        )
                                        .then(
                                            if (picked) {
                                                Modifier.border(
                                                    Dimens.Calendar.ChipInset,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(Dimens.CornerXs),
                                                )
                                            } else Modifier,
                                        ),
                                ) {
                                    if (b.label != null) {
                                        Row {
                                            Box(
                                                modifier = Modifier
                                                    .width(Dimens.Calendar.BlockAccentBarWidth)
                                                    .fillMaxHeight()
                                                    .background(calendarAccent),
                                            )
                                            val short =
                                                b.endMin - b.startMin <= SHORT_BLOCK_MIN
                                            // 窄列(compactBlocks)不显时间;短块仅一行,
                                            // 长块标题可占两行,把腾出的行留给标题。
                                            val showTime = !compactBlocks && b.timeLabel != null
                                            Column(
                                                modifier = Modifier.padding(
                                                    horizontal = Dimens.SpaceXxs, vertical = Dimens.BorderThin,
                                                ),
                                            ) {
                                                val fg = textOf.getValue(visual)
                                                // 拒绝与取消同样划掉:两者都是「这场我不去」。
                                                val strike =
                                                    if (b.faded || declined) {
                                                        TextDecoration.LineThrough
                                                    } else null
                                                Text(
                                                    text = if (short && showTime) {
                                                        "${b.label}$titleTimeSep${b.timeLabel}"
                                                    } else b.label,
                                                    style = WeMeetTextStyles.LabelTiny,
                                                    color = fg,
                                                    maxLines = if (short) 1 else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textDecoration = strike,
                                                )
                                                if (!short && showTime) {
                                                    Text(
                                                        text = b.timeLabel!!,
                                                        style = WeMeetTextStyles.LabelMicro,
                                                        color = fg.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textDecoration = strike,
                                                    )
                                                }
                                            }
                                        }
                                    } else if (b.timeLabel != null &&
                                        b.endMin - b.startMin >= BUSY_TIME_LABEL_MIN
                                    ) {
                                        // 无标题的忙闲块(他人日程,只给区间不给内容):
                                        // 块高够就把时段写上 —— 全空白读不出这是几点
                                        // 到几点,尤其块被裁过或列很窄时。
                                        Text(
                                            text = b.timeLabel,
                                            style = WeMeetTextStyles.LabelMicro,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(
                                                horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs,
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        // 选中态日程块的上下圆抓手(与预选框同款,骑在边界线上):
                        // 拖它改时长。拖动中改由下面的落点预览接管,先撤掉。
                        if (selectedBlockKey != null && movePreview == null) {
                            columns.forEachIndexed { i, blocks ->
                                blocks
                                    .firstOrNull { it.key == selectedBlockKey && it.movable }
                                    ?.let { sb ->
                                        val inset = minOf(DRAFT_HANDLE_INSET, colWidth / 3)
                                        val baseX = colWidth * i
                                        DraftHandle(
                                            modifier = Modifier.offset(
                                                x = baseX + colWidth - inset -
                                                    DRAFT_HANDLE_SIZE / 2,
                                                y = minuteY(sb.startMin.coerceIn(rangeStart, rangeEnd)) -
                                                    DRAFT_HANDLE_SIZE / 2,
                                            ),
                                        )
                                        DraftHandle(
                                            modifier = Modifier.offset(
                                                x = baseX + inset - DRAFT_HANDLE_SIZE / 2,
                                                y = minuteY(sb.endMin.coerceIn(rangeStart, rangeEnd)) -
                                                    DRAFT_HANDLE_SIZE / 2,
                                            ),
                                        )
                                    }
                            }
                        }

                        // 日程块拖动中的落点预览:主色描边 + 新时段,松手才落库。
                        movePreview?.let { mv ->
                            val mvTop = minuteY(mv.startMin)
                            val mvHeight =
                                (hourHeight * ((mv.endMin - mv.startMin) / 60f))
                                    .coerceAtLeast(Dimens.Calendar.MovePreviewMinHeight)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .offset(x = colWidth * mv.colIndex, y = mvTop)
                                    .width(colWidth)
                                    .height(mvHeight)
                                    .padding(horizontal = Dimens.Calendar.ChipInset)
                                    .clip(RoundedCornerShape(Dimens.CornerXs))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    )
                                    .border(
                                        Dimens.BorderThin,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(Dimens.CornerXs),
                                    ),
                            ) {
                                Text(
                                    text = "${fmtMin(mv.startMin)} – ${fmtMin(mv.endMin)}",
                                    style = WeMeetTextStyles.LabelTiny,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // 预选时段(飞书样式):浅蓝底 + 主色描边 + 「添加日程」,
                        // 右上/左下各一个圆手柄骑在边界线上(拖它改起止)。
                        if (draft != null && draftLabel != null) {
                            val dTop = minuteY(draft.startMin)
                            val dHeight =
                                (hourHeight * ((draft.endMin - draft.startMin) / 60f))
                                    .coerceAtLeast(DRAFT_HANDLE_SIZE)
                            val inset = minOf(DRAFT_HANDLE_INSET, colWidth / 3)
                            val baseX = colWidth * draft.colIndex
                            val draftAccent = if (draftConflict) {
                                selectionConflictColor
                            } else MaterialTheme.colorScheme.primary
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .offset(x = baseX, y = dTop)
                                    .width(colWidth)
                                    .height(dHeight)
                                    .padding(horizontal = Dimens.Calendar.ChipInset)
                                    .clip(RoundedCornerShape(Dimens.CornerS))
                                    .background(
                                        draftAccent.copy(alpha = 0.12f),
                                    )
                                    .border(
                                        Dimens.BorderThin,
                                        draftAccent,
                                        RoundedCornerShape(Dimens.CornerS),
                                    ),
                            ) {
                                Text(
                                    text = draftLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = draftAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = Dimens.SpaceXxs),
                                )
                            }
                            DraftHandle(
                                accent = draftAccent,
                                modifier = Modifier.offset(
                                    x = baseX + colWidth - inset - DRAFT_HANDLE_SIZE / 2,
                                    y = dTop - DRAFT_HANDLE_SIZE / 2,
                                ),
                            )
                            DraftHandle(
                                accent = draftAccent,
                                modifier = Modifier.offset(
                                    x = baseX + inset - DRAFT_HANDLE_SIZE / 2,
                                    y = dTop + dHeight - DRAFT_HANDLE_SIZE / 2,
                                ),
                            )
                        }

                        // 当前时刻红线(仅 nowLineInColumn 的列)。圆点只画在最左那
                        // 一列 —— 忙闲页每列都是「今天」,每列一个点会串成一串珠子。
                        if (nowMinute != null && nowMinute in rangeStart..rangeEnd) {
                            val y = minuteY(nowMinute)
                            val firstLineCol = (0 until n).firstOrNull { nowLineInColumn(it) }
                            for (i in 0 until n) {
                                if (!nowLineInColumn(i)) continue
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i, y = y - Dimens.BorderThin)
                                        .width(colWidth)
                                        .height(Dimens.Calendar.NowLineThickness)
                                        .background(nowLineColor),
                                )
                                if (i == firstLineCol) {
                                    Box(
                                        modifier = Modifier
                                            .offset(x = colWidth * i - Dimens.SpaceXxs, y = y - Dimens.SpaceXxs)
                                            .width(Dimens.Calendar.NowLineDotSize)
                                            .height(Dimens.Calendar.NowLineDotSize)
                                            .background(nowLineColor, CircleShape),
                                    )
                                }
                            }
                        }

                        // 选中时段:横贯所有列(忙闲页)。冲突时整框转红(底 + 边框)。
                        if (selection != null &&
                            selection.startMin < rangeEnd && selection.endMin > rangeStart
                        ) {
                            val clippedStart = maxOf(selection.startMin, rangeStart)
                            val clippedEnd = minOf(selection.endMin, rangeEnd)
                            val selTop = minuteY(clippedStart)
                            val selHeight =
                                (hourHeight * ((clippedEnd - clippedStart) / 60f))
                                    .coerceAtLeast(Dimens.Calendar.SelectionMinHeight)
                            val selAccent = if (selectionConflict) {
                                selectionConflictColor
                            } else MaterialTheme.colorScheme.primary
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = selTop)
                                    .height(selHeight)
                                    .padding(horizontal = Dimens.BorderThin)
                                    .background(
                                        color = selAccent.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(Dimens.CornerXs),
                                    )
                                    .then(
                                        if (onSelectionAdjust != null) {
                                            Modifier.border(
                                                Dimens.BorderThin, selAccent, RoundedCornerShape(Dimens.CornerXs),
                                            )
                                        } else Modifier,
                                    ),
                            )
                            // 上下抓手(与预选框同款):贴视口右上 / 左下,滚动也在视野内
                            // —— 选段横贯所有列,贴内容边缘的话人多时会滚出屏幕。
                            if (onSelectionAdjust != null) {
                                val handlePx = with(density) { DRAFT_HANDLE_SIZE.toPx() }
                                val insetPx = with(density) { DRAFT_HANDLE_INSET.toPx() }
                                val topPx = minuteYPx(selection.startMin)
                                val botPx = minuteYPx(selection.endMin)
                                DraftHandle(
                                    accent = selAccent,
                                    // offset {} 在布局阶段读 hScroll,滚动时不触发重组。
                                    modifier = Modifier.offset {
                                        IntOffset(
                                            ((horizontalContentOffsetPx?.invoke()
                                                ?: hScroll.value.toFloat()) +
                                                availablePx - insetPx -
                                                handlePx / 2).roundToInt(),
                                            (topPx - handlePx / 2).roundToInt(),
                                        )
                                    },
                                )
                                DraftHandle(
                                    accent = selAccent,
                                    modifier = Modifier.offset {
                                        IntOffset(
                                            ((horizontalContentOffsetPx?.invoke()
                                                ?: hScroll.value.toFloat()) +
                                                insetPx - handlePx / 2)
                                                .roundToInt(),
                                            (botPx - handlePx / 2).roundToInt(),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/** 忙闲页选段撞上他人日程时的红(与 Web 的冲突色同档)。 */

/**
 * 45° 斜纹 + 虚线圆角框:忙闲页「对方尚未回复」的块用(见 [TimeBlock.hatched])。
 * 画在底色之上、文字之下,所以走 drawBehind 而不是 background。
 */
private fun Modifier.hatchedOutline(accent: Color): Modifier = drawBehind {
    val gap = Dimens.Calendar.NowLineDashGap.toPx()
    val hatch = accent.copy(alpha = 0.35f)
    // 从左上角外一个身位起画,保证左右两端都铺满(斜线跨度 = 块高)。
    var x = -size.height
    while (x < size.width) {
        drawLine(
            color = hatch,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = Dimens.Calendar.NowLineStroke.toPx(),
        )
        x += gap
    }
    val stroke = Dimens.BorderThin.toPx()
    drawRoundRect(
        color = accent.copy(alpha = 0.8f),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(Dimens.CornerXs.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
        ),
    )
}

/**
 * 等指针移出触摸 slop 才算「要拖了」:期间松手 → false(按点击处理,事件不
 * 消费,留给 Canvas 的 tap 检测)。返回 true 时首个越界事件已被消费。
 */
private suspend fun AwaitPointerEventScope.awaitSlopExceeded(
    pointerId: PointerId,
    origin: Offset,
    slopPx: Float,
): Boolean {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val ch = ev.changes.firstOrNull { it.id == pointerId } ?: return false
        if (!ch.pressed) return false
        if ((ch.position - origin).getDistance() > slopPx) {
            ch.consume()
            return true
        }
    }
}

/**
 * 拖上下抓手改起止的公共循环(预选框 / 选中态日程块 / 忙闲页选段三处共用):
 * [movingStart] 决定动哪一头,吸附到 [DRAFT_SNAP_MIN] 并保最短一格;每帧把
 * 新起止交给 [emit],松手返回最终值。调用方负责先 consume 掉 down。
 */
private suspend fun AwaitPointerEventScope.dragEdge(
    pointerId: PointerId,
    hourHeightPx: Float,
    startMin: Int,
    endMin: Int,
    movingStart: Boolean,
    visibleStartMin: Int = 0,
    visibleEndMin: Int = 24 * 60,
    emit: (Int, Int) -> Unit,
): Pair<Int, Int> {
    var s = startMin
    var e = endMin
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
        ch.consume()
        if (!ch.pressed) break
        val m = snapMinuteAt(
            ch.position.y,
            hourHeightPx,
            visibleStartMin,
            visibleEndMin,
        )
        if (movingStart) {
            s = m.coerceIn(visibleStartMin, e - DRAFT_MIN_DURATION)
        } else {
            e = m.coerceIn(s + DRAFT_MIN_DURATION, visibleEndMin)
        }
        emit(s, e)
    }
    return s to e
}

/**
 * 长按选中之后:同一手势里继续等 —— 移动超 slop → true(接着整块拖),抬手
 * → false(停在选中态)。期间事件一律消费,否则抬手会被 tap 检测当成点击
 * 直接弹详情。
 */
private suspend fun AwaitPointerEventScope.awaitDragAfterSelect(
    pointerId: PointerId,
    origin: Offset,
    slopPx: Float,
): Boolean {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val ch = ev.changes.firstOrNull { it.id == pointerId } ?: return false
        ch.consume()
        if (!ch.pressed) return false
        if ((ch.position - origin).getDistance() > slopPx) return true
    }
}

/**
 * 等长按成立(超时 = 成立):中途松手 = 点击(false),中途划出 slop = 用户
 * 想滚动(false,不消费,滚动照常接管)。日程块改期用它兜误触。
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(
    pointerId: PointerId,
    origin: Offset,
    slopPx: Float,
    timeoutMs: Long,
): Boolean = withTimeoutOrNull(timeoutMs) {
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val ch = ev.changes.firstOrNull { it.id == pointerId }
            ?: return@withTimeoutOrNull false
        if (!ch.pressed) return@withTimeoutOrNull false
        if ((ch.position - origin).getDistance() > slopPx) {
            return@withTimeoutOrNull false
        }
    }
    @Suppress("UNREACHABLE_CODE") false
} ?: true

/**
 * 预选块 / 选段的边界抓手:白心 + 主色圈(纯视觉,手势在网格 Box 上按坐标
 * 命中)。[accent] 缺省主色;忙闲页选段冲突时传红色,与框同色。
 */
@Composable
private fun DraftHandle(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(DRAFT_HANDLE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(Dimens.BorderEmphasis, accent, CircleShape),
    )
}
