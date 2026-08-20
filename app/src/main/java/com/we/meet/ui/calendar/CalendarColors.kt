package com.we.meet.ui.calendar

import androidx.compose.ui.graphics.Color

/** Stable payload values accepted by the existing calendar subscription API. */
val CALENDAR_COLOR_PALETTE = listOf(
    "#3370ff",
    "#5b8ff9",
    "#34c724",
    "#5ad8a6",
    "#f5a623",
    "#f6bd16",
    "#f54a45",
    "#e8684a",
    "#8b5cf6",
    "#9270ca",
    "#6dc8ec",
    "#5d7092",
)

/** Parses only six-digit RGB payloads. Invalid server data never reaches Color constructors. */
fun parseCalendarColor(value: String?): Color? {
    val normalized = value?.trim()?.removePrefix("#") ?: return null
    if (normalized.length != 6) return null
    val rgb = normalized.toLongOrNull(16) ?: return null
    // design-exempt: 这不是在调用处凭空写一个色值,而是把**服务端下发的**日历
    // 颜色解析成 Color(上面已校验长度与进制,非法输入返回 null)。token 化在
    // 这里无从谈起 —— 值是运行时才知道的。
    return Color(
        red = ((rgb shr 16) and 0xff).toFloat() / 255f,
        green = ((rgb shr 8) and 0xff).toFloat() / 255f,
        blue = (rgb and 0xff).toFloat() / 255f,
        alpha = 1f,
    )
}

fun validCalendarColorOrDefault(value: String?): String =
    value?.takeIf { parseCalendarColor(it) != null } ?: CALENDAR_COLOR_PALETTE.first()
