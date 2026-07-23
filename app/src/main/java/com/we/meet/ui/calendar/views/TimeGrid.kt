package com.we.meet.ui.calendar.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.ui.calendar.EventUi
import java.time.LocalDate
import java.util.Locale

/**
 * P8 纵向时间轴的共用积木:日视图(1 列)/周视图(7 列)/忙闲对比页(一人一列)
 * 共享同一份实现 —— Canvas 画格线 + offset 摆块 + verticalScroll,整格
 * pointerInput 命中(块 → onBlockTap,空白 → onSlotTap)。
 *
 * P8-UX 修正(对齐飞书):传 [minColumnWidth] 时列宽弹性但有下限,列多时
 * **列头 + 网格整体横向滚动**(共享同一 hScroll);列头由 [columnHeader]
 * 渲染在网格上方并与列严格对齐。不传时保持等分布局(日/周视图不变)。
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
)

/** 短块阈值(分钟):≤45 分钟块高只够一行,时间并入标题行(对齐 Web)。 */
internal const val SHORT_BLOCK_MIN = 45

private fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** 忙闲页选中的时段(横贯所有列的高亮框)。 */
data class TimeSelection(val startMin: Int, val endMin: Int)

/**
 * 把日程投影成 [date] 当日的时间块;全天/不覆盖当日 → null。
 * [dimPastNow] 非空时,结束时刻早于它的块标记 dimmed(P8 日历设置)。
 */
fun EventUi.toTimeBlockOrNull(
    date: LocalDate,
    dimPastNow: java.time.ZonedDateTime? = null,
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
    )
}

/** 左侧小时刻度列(00:00–23:00)。 */
@Composable
fun HourRail(hourHeight: Dp, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(HOUR_RAIL_WIDTH)) {
        repeat(24) { h ->
            Text(
                text = "%02d:00".format(h),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hourHeight)
                    .padding(end = 4.dp),
            )
        }
    }
}

val HOUR_RAIL_WIDTH = 44.dp

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
    hourHeight: Dp = 56.dp,
    scrollState: ScrollState = rememberScrollState(),
    nowMinute: Int? = null,
    nowLineInColumn: (Int) -> Boolean = { true },
    disabledColumn: (Int) -> Boolean = { false },
    selection: TimeSelection? = null,
    selectionConflict: Boolean = false,
    onSlotTap: ((colIndex: Int, minuteOfDay: Int) -> Unit)? = null,
    onBlockTap: ((colIndex: Int, key: String) -> Unit)? = null,
    minColumnWidth: Dp? = null,
    columnHeader: (@Composable (colIndex: Int) -> Unit)? = null,
) {
    val n = columns.size.coerceAtLeast(1)
    val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val offWorkShade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
    val busyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val eventBg = MaterialTheme.colorScheme.primaryContainer
    val eventFg = MaterialTheme.colorScheme.onPrimaryContainer
    val accentColor = MaterialTheme.colorScheme.primary
    // 短块「标题,时间」分隔符:中文全角逗号(对齐 Web),其他语言半角。
    val titleTimeSep = if (Locale.getDefault().language == "zh") "，" else ", "
    val density = LocalDensity.current
    // 列头与网格共享一个横向 ScrollState → 严格同步滚动(飞书样式)。
    val hScroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        val available = maxWidth - HOUR_RAIL_WIDTH
        val equalSplit = available / n
        val colWidth: Dp =
            if (minColumnWidth != null && equalSplit < minColumnWidth) minColumnWidth
            else equalSplit
        val contentWidth = colWidth * n
        val hourHeightPx = with(density) { hourHeight.toPx() }
        val colWidthPx = with(density) { colWidth.toPx() }

        Column(modifier = Modifier.fillMaxSize()) {
            if (columnHeader != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(HOUR_RAIL_WIDTH))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(hScroll),
                    ) {
                        for (i in 0 until n) {
                            Box(modifier = Modifier.width(colWidth)) { columnHeader(i) }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
                HourRail(hourHeight)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll),
                ) {
                    Box(
                        modifier = Modifier
                            .width(contentWidth)
                            .height(hourHeight * 24),
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(n, hourHeightPx, colWidthPx, columns) {
                                    detectTapGestures { off ->
                                        val col =
                                            (off.x / colWidthPx).toInt().coerceIn(0, n - 1)
                                        val minute = ((off.y / hourHeightPx) * 60)
                                            .toInt()
                                            .coerceIn(0, 24 * 60 - 1)
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
                            drawRect(
                                offWorkShade,
                                size = Size(size.width, 9 * hourHeightPx),
                            )
                            drawRect(
                                offWorkShade,
                                topLeft = Offset(0f, 18 * hourHeightPx),
                                size = Size(size.width, 6 * hourHeightPx),
                            )
                            for (h in 0..24) {
                                val y = h * hourHeightPx
                                drawLine(
                                    gridLine,
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
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
                            blocks.forEach { b ->
                                val top = hourHeight * (b.startMin / 60f)
                                val blockHeight =
                                    (hourHeight * ((b.endMin - b.startMin) / 60f))
                                        .coerceAtLeast(10.dp)
                                // 对齐 Web/飞书:左侧主色竖条 + 浅色底;短块(≤45min)
                                // 时间并入标题行,长块标题行+时间行。
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i, y = top)
                                        .width(colWidth)
                                        .height(blockHeight)
                                        .padding(horizontal = 1.5.dp, vertical = 1.dp)
                                        // P8「降低已结束日程的亮度」:整块(底+文字)降透明。
                                        .alpha(if (b.dimmed) 0.5f else 1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            color = if (b.label != null) {
                                                if (b.faded) eventBg.copy(alpha = 0.45f)
                                                else eventBg
                                            } else busyColor,
                                        ),
                                ) {
                                    if (b.label != null) {
                                        Row {
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .fillMaxHeight()
                                                    .background(accentColor),
                                            )
                                            val short =
                                                b.endMin - b.startMin <= SHORT_BLOCK_MIN
                                            Column(
                                                modifier = Modifier.padding(
                                                    horizontal = 3.dp, vertical = 1.dp,
                                                ),
                                            ) {
                                                Text(
                                                    text = if (short && b.timeLabel != null) {
                                                        "${b.label}$titleTimeSep${b.timeLabel}"
                                                    } else b.label,
                                                    fontSize = 10.sp,
                                                    lineHeight = 12.sp,
                                                    color = eventFg,
                                                    maxLines = if (short) 1 else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textDecoration =
                                                        if (b.faded) TextDecoration.LineThrough
                                                        else null,
                                                )
                                                if (!short && b.timeLabel != null) {
                                                    Text(
                                                        text = b.timeLabel,
                                                        fontSize = 9.sp,
                                                        lineHeight = 11.sp,
                                                        color = eventFg.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 当前时刻红线(仅 nowLineInColumn 的列)。
                        if (nowMinute != null) {
                            val y = hourHeight * (nowMinute / 60f)
                            for (i in 0 until n) {
                                if (!nowLineInColumn(i)) continue
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i, y = y - 1.dp)
                                        .width(colWidth)
                                        .height(2.dp)
                                        .background(NowLineColor),
                                )
                                Box(
                                    modifier = Modifier
                                        .offset(x = colWidth * i - 2.dp, y = y - 3.dp)
                                        .width(6.dp)
                                        .height(6.dp)
                                        .background(NowLineColor, CircleShape),
                                )
                            }
                        }

                        // 选中时段:横贯所有列(忙闲页)。
                        if (selection != null) {
                            val selTop = hourHeight * (selection.startMin / 60f)
                            val selHeight =
                                (hourHeight * ((selection.endMin - selection.startMin) / 60f))
                                    .coerceAtLeast(6.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = selTop)
                                    .height(selHeight)
                                    .padding(horizontal = 1.dp)
                                    .background(
                                        color = if (selectionConflict) {
                                            Color(0xFFDC2626).copy(alpha = 0.18f)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                                .copy(alpha = 0.18f)
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal val NowLineColor = Color(0xFFEF4444)
