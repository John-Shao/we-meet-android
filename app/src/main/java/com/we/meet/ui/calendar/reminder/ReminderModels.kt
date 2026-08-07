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

    /** 进入这条日程的提醒窗口 →「N 分钟后」。 */
    data class Soon(val minutes: Int) : ReminderBadge
}

/**
 * 没有设提前量时的角标窗口(分钟)。
 *
 * 这**不是**「默认提醒时间」—— 是「这条日程没告诉我们什么时候该紧张,那就
 * 快开始时提一下」。设了提前量的日程一律按它自己的来。
 */
const val DEFAULT_COUNTDOWN_WINDOW_MINUTES = 60

/**
 * 角标窗口 = 这条日程**自己设的**最大提前量,没设则退 60 分钟。
 *
 * ⚠️ 以前这里是写死的 60 分钟,跟 `reminders` 毫无关系 —— 于是用户在日程上
 * 选「提前 10 分钟」,列表入口该亮的时候不亮、不该亮的时候亮了 50 分钟。
 * 真机上是这么发现的:一条设了提醒的日程到点什么都没有,查下去才发现**那个
 * 开关在这条路径上什么都不驱动**(服务端那条 IM 提醒又因为房间没有会议群被
 * 静默丢弃)。
 *
 * 取**最大**而不是最小:多个提前量意味着「从最早那次开始就该被看见」,与服务端
 * `calendar_reminders._lead_minutes` 和 Web `countdownWindowMinutes` 同一口径 ——
 * 三处不一致的话,IM 提醒到了而角标还没亮,或者反过来。
 */
fun countdownWindowMinutes(event: EventUi): Int =
    event.reminders.filter { it > 0 }.maxOrNull() ?: DEFAULT_COUNTDOWN_WINDOW_MINUTES

/**
 * 角标口径与 Web `reminderWindow.ts` 一致;全天日程不参与倒计时。
 *
 * **按秒比,不按分钟比。** 原来是 `toMinutes() in 0..59` —— `toMinutes()` 截尾,
 * 于是「还差 60 分 30 秒」被截成 60、落在窗口外,而 Web 那边 `60.5 <= 60` 也是
 * 假,两边碰巧一致;但一旦窗口换成用户设的值,截尾就会让「提前 10 分钟」在第
 * 10 分钟整**不亮**(10 不在 `0 until 10` 里),而 Web 的 `<= 10` 是亮的。
 * 用户设 10 分钟,就该在第 10 分钟看见 —— 所以闭区间,且比较放到秒上做。
 */
fun reminderBadge(event: EventUi, now: ZonedDateTime): ReminderBadge? {
    if (event.allDay) return null
    if (!event.start.isAfter(now) && event.end.isAfter(now)) return ReminderBadge.Now
    val seconds = Duration.between(now, event.start).seconds
    if (seconds <= 0 || seconds > countdownWindowMinutes(event) * 60L) return null
    // 与 Web 的 Math.ceil 对齐:还剩 30 秒时显示「1 分钟后」,不是「0 分钟后」。
    return ReminderBadge.Soon(((seconds + 59) / 60).toInt())
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** 「HH:mm - HH:mm」;全天 → null(调用方显示「全天」)。 */
fun reminderTimeRange(event: EventUi): String? =
    if (event.allDay) null
    else "${event.start.format(TIME_FMT)} - ${event.end.format(TIME_FMT)}"
