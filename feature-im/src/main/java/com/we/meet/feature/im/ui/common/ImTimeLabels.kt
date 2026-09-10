package com.we.meet.feature.im.ui.common

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 会话列表与聊天记录共用的时间分档(微信式):今天 → 时分;昨天 → 「昨天」;
 * 一周内 → 「周X」;更早 → 「9月2日」(跨年再带上年份)。
 *
 * 这两处原先各写一份,而且都把格式串写死 —— 中文下日期显示成「9/2」而不是
 * 「9月2日」,隔了几天也不显示星期。收在一处是为了「改一处生效」。
 *
 * [locale] 一律传**应用内语言**(调用点取 `com.we.meet.ui.locale.appLocale`),
 * 不要用 `Locale.getDefault()`:后者是设备语言,与资源串(如「昨天」)的语言可能
 * 不同,会把一条标签串成两种语言。Web 端同一个口径用的是 i18n.language。
 */

/** 满这么多天就不再显示星期几,改回具体日期(即 2~6 天前显示「周X」)。 */
private const val WEEKDAY_LABEL_MAX_DAYS = 7L

/** 时分的 skeleton;系统按语言给出具体格式(中英文都是 HH:mm)。 */
private const val TIME_SKELETON = "Hm"

/** 会话列表右上角的时间戳。 */
internal fun imConversationTimeLabel(tsMs: Long, yesterday: String, locale: Locale): String {
    val then = zonedTime(tsMs)
    return dayTierLabel(then, yesterday, locale) ?: then.formatSkeleton(locale, TIME_SKELETON)
}

/** 聊天记录里居中的时间分隔条:今天只有时分,其余是「<天> 时分」。 */
internal fun imDividerTimeLabel(tsMs: Long, yesterday: String, locale: Locale): String {
    val then = zonedTime(tsMs)
    val time = then.formatSkeleton(locale, TIME_SKELETON)
    val day = dayTierLabel(then, yesterday, locale) ?: return time
    return "$day $time"
}

/** 时间戳 → 设备时区下的时间点。 */
private fun zonedTime(tsMs: Long): ZonedDateTime =
    Instant.ofEpochMilli(tsMs).atZone(ZoneId.systemDefault())

/**
 * 「天」的分档:今天返回 null(调用方只显示时分),其余返回「昨天」「周X」「9月2日」。
 *
 * 星期与日期不写死格式串,交给 [DateFormat.getBestDateTimePattern] 按当前语言取最佳
 * 格式:中文出「周五」「9月2日」,英文出「Fri」「Sep 2」。
 */
private fun dayTierLabel(then: ZonedDateTime, yesterday: String, locale: Locale): String? {
    val today = LocalDate.now(then.zone)
    // 设备时钟回拨会让时间戳落在「未来」,一并按今天处理,别掉到日期档。
    val daysAgo = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    return when {
        daysAgo <= 0L -> null
        daysAgo == 1L -> yesterday
        daysAgo < WEEKDAY_LABEL_MAX_DAYS -> then.formatSkeleton(locale, "EEE")
        then.year == today.year -> then.formatSkeleton(locale, "MMMd")
        else -> then.formatSkeleton(locale, "yMMMd")
    }
}

/** skeleton → 当前语言的最佳格式串,再渲染(zh:「EEE」→ 周五、「MMMd」→ 9月2日)。 */
private fun ZonedDateTime.formatSkeleton(locale: Locale, skeleton: String): String =
    format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
