package com.we.meet.ui.calendar.reminder

import com.we.meet.ui.calendar.EventUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 消息列表「日程提醒」入口的倒计时角标。
 *
 * 以前窗口是写死的 60 分钟,跟日程自己的 `reminders` 毫无关系 —— 用户选
 * 「提前 10 分钟」之后,入口该亮时不亮、不该亮时亮了 50 分钟。真机上是这么
 * 发现的:一条设了提醒的日程到点什么都没有,查下去才发现**那个开关在这条
 * 路径上什么都不驱动**(服务端那条 IM 提醒又因为房间没有会议群被静默丢弃)。
 *
 * 三处口径必须一致 —— 服务端 `calendar_reminders._lead_minutes`、Web
 * `reminderWindow.countdownWindowMinutes`、这里。不一致的表现是 IM 提醒到了
 * 而角标还没亮,或者反过来。
 */
class ReminderBadgeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 7, 15, 50, 0, 0, zone)

    private fun event(
        startMinutesFromNow: Long,
        reminders: List<Int> = emptyList(),
        allDay: Boolean = false,
    ) = EventUi(
        id = "e",
        title = "一周回顾",
        start = now.plusMinutes(startMinutesFromNow),
        end = now.plusMinutes(startMinutesFromNow + 60),
        allDay = allDay,
        myRsvp = null,
        roomSlug = null,
        organizerName = null,
        cancelled = false,
        reminders = reminders,
    )

    @Test
    fun `设了 10 分钟 —— 11 分钟前不亮,10 分钟前亮`() {
        assertNull(reminderBadge(event(11, listOf(10)), now))
        assertEquals(ReminderBadge.Soon(10), reminderBadge(event(10, listOf(10)), now))
    }

    @Test
    fun `多个提前量取最大 —— 与服务端 _lead_minutes 同口径`() {
        assertEquals(30, countdownWindowMinutes(event(0, listOf(5, 30, 15))))
    }

    @Test
    fun `没设提前量退 60 分钟 —— 那是兜底窗口,不是默认提醒时间`() {
        assertEquals(
            DEFAULT_COUNTDOWN_WINDOW_MINUTES,
            countdownWindowMinutes(event(0)),
        )
        assertEquals(ReminderBadge.Soon(45), reminderBadge(event(45), now))
    }

    @Test
    fun `窗口外不亮`() {
        assertNull(reminderBadge(event(61), now))
        assertNull(reminderBadge(event(20, listOf(10)), now))
    }

    @Test
    fun `边界按秒判,不被 toMinutes 截尾吃掉`() {
        // 还差 10 分 30 秒:超出 10 分钟窗口,不该亮。截尾成 10 的话就亮了。
        val e = EventUi(
            id = "e",
            title = "t",
            start = now.plusMinutes(10).plusSeconds(30),
            end = now.plusMinutes(70),
            allDay = false,
            myRsvp = null,
            roomSlug = null,
            organizerName = null,
            cancelled = false,
            reminders = listOf(10),
        )
        assertNull(reminderBadge(e, now))
    }

    @Test
    fun `不足一分钟显示「1 分钟后」而不是 0 —— 与 Web 的 ceil 对齐`() {
        val e = EventUi(
            id = "e",
            title = "t",
            start = now.plusSeconds(30),
            end = now.plusMinutes(60),
            allDay = false,
            myRsvp = null,
            roomSlug = null,
            organizerName = null,
            cancelled = false,
            reminders = listOf(10),
        )
        assertEquals(ReminderBadge.Soon(1), reminderBadge(e, now))
    }

    @Test
    fun `脏数据不把窗口算成 0 —— 算成 0 等于角标永不亮`() {
        assertEquals(
            DEFAULT_COUNTDOWN_WINDOW_MINUTES,
            countdownWindowMinutes(event(0, listOf(0, -5))),
        )
    }

    @Test
    fun `进行中永远是「现在」,不看提前量`() {
        assertEquals(ReminderBadge.Now, reminderBadge(event(-5, listOf(5)), now))
    }

    @Test
    fun `全天日程不参与倒计时`() {
        assertNull(reminderBadge(event(10, listOf(10), allDay = true), now))
    }
}
