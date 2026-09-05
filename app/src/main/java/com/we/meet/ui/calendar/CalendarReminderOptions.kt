package com.we.meet.ui.calendar

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.we.meet.R

/**
 * 日程提醒的可选提前量(分钟),双端同一份 —— Web 侧对应
 * `frontend/src/features/calendar/hooks/useCalendarSettings.ts` 的
 * `REMINDER_OPTIONS`,改这里请一起改那边。
 *
 * 0 = 日程开始时;60/120 = 提前 1/2 小时;1440/2880 = 提前 1/2 天。
 * 「不提醒」不在表里 —— 它是 null,由各处自己作为首项给出。
 *
 * 新建表单、日历设置的默认提醒都读这一份,免得三处各写一份再各自漂移。
 */
val REMINDER_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880)

/**
 * 提醒提前量文案。null = 不提醒;整天/整小时走「天/小时」文案(1 与 n 分开,
 * 英法等语言的单复数不能靠 %d 糊过去),其余按分钟。
 *
 * 取 Resources 而不是 @Composable,好让 joinToString 这类 lambda 里也能用。
 */
fun reminderLabel(res: Resources, minutes: Int?): String = when {
    minutes == null -> res.getString(R.string.calendar_reminder_none)
    minutes == 0 -> res.getString(R.string.calendar_reminder_at_time)
    minutes % 1440 == 0 -> {
        val days = minutes / 1440
        res.getQuantityString(R.plurals.calendar_reminder_days, days, days)
    }
    minutes % 60 == 0 -> {
        val hours = minutes / 60
        res.getQuantityString(R.plurals.calendar_reminder_hours, hours, hours)
    }
    else -> res.getQuantityString(R.plurals.calendar_reminder_minutes, minutes, minutes)
}

/** Compose 里的便捷重载。 */
@Composable
fun reminderLabel(minutes: Int?): String =
    reminderLabel(LocalContext.current.resources, minutes)
