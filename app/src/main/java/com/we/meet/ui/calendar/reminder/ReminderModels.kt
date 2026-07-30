package com.we.meet.ui.calendar.reminder

import com.we.meet.data.api.CalendarApi
import com.we.meet.ui.calendar.EventUi
import com.we.meet.ui.calendar.toParsed
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * P8「在消息列表提醒日程」(对标飞书)数据侧:窗口拉取 + 今日/明日分桶 +
 * 最近日程 + 倒计时角标。复用日历 tab 的 [EventUi]/[toParsed] 口径。
 */

data class ReminderWindow(
    val today: List<EventUi>,
    val tomorrow: List<EventUi>,
)

/** 今天未结束的最早一条 —— 入口行与提醒页横幅的主角。 */
fun ReminderWindow.nearest(now: ZonedDateTime): EventUi? =
    today.firstOrNull { it.end.isAfter(now) }

/**
 * 「这场会我还会去」—— 提醒类界面统一用这一条(口径与 Web reminderWindow.ts
 * 的 shouldRemind 一致,改一处请两端同步)。
 *
 * 排除已取消和**我已拒绝**的。提醒是「行动」面:拒了就是不去,提醒你去是纯
 * 噪音,横幅上那个「进入会议」更是错的;而且 nearest 只取最早一条未结束的,
 * 一场拒掉的会会把真正要去的那场顶下去。日历里照旧灰显 + 删除线 —— 那是
 * 「看全貌/可以改主意」的地方,和提醒面刻意不同口径。
 */
fun EventUi.shouldRemind(): Boolean = !cancelled && myRsvp != "declined"

/** 拉取 [今天 00:00, 后天 00:00) 的日程并分桶;不去的排除,按开始时间升序。 */
suspend fun loadReminderWindow(calendarApi: CalendarApi): ReminderWindow {
    val zone = ZoneId.systemDefault()
    val today0 = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone)
    val tomorrow0 = today0.plusDays(1)
    val after0 = today0.plusDays(2)

    val all = mutableListOf<EventUi>()
    var page = 1
    while (page <= 3) {
        val res = calendarApi.listEvents(
            page = page,
            start = DateTimeFormatter.ISO_INSTANT.format(today0.toInstant()),
            end = DateTimeFormatter.ISO_INSTANT.format(after0.toInstant()),
        )
        all += res.results.mapNotNull { it.toParsed()?.ui }
        if (res.next == null) break
        page++
    }
    val active = all.filter { it.shouldRemind() }.sortedBy { it.start }
    fun overlaps(e: EventUi, from: ZonedDateTime, to: ZonedDateTime): Boolean =
        e.start.isBefore(to) && e.end.isAfter(from)
    return ReminderWindow(
        today = active.filter { overlaps(it, today0, tomorrow0) },
        tomorrow = active.filter { overlaps(it, tomorrow0, after0) },
    )
}

sealed interface ReminderBadge {
    /** 进行中 →「现在」。 */
    data object Now : ReminderBadge

    /** 1 小时内开始 →「N 分钟后」。 */
    data class Soon(val minutes: Int) : ReminderBadge
}

/** 角标口径与 Web reminderWindow.ts 一致;全天日程不参与倒计时。 */
fun reminderBadge(event: EventUi, now: ZonedDateTime): ReminderBadge? {
    if (event.allDay) return null
    if (!event.start.isAfter(now) && event.end.isAfter(now)) return ReminderBadge.Now
    val minutes = Duration.between(now, event.start).toMinutes()
    if (minutes in 0..59) return ReminderBadge.Soon(minutes.toInt().coerceAtLeast(1))
    return null
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 「HH:mm - HH:mm」;全天 → null(调用方显示「全天」)。 */
fun reminderTimeRange(event: EventUi): String? =
    if (event.allDay) null
    else "${event.start.format(TIME_FMT)} - ${event.end.format(TIME_FMT)}"
