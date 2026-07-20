package com.we.meet.ui.calendar.views

import androidx.annotation.StringRes
import com.we.meet.R

/** P8:日历 tab 视图模式(对标飞书,「三日」按团队约定改为「周」)。 */
enum class CalendarViewMode(@StringRes val labelRes: Int) {
    AGENDA(R.string.calendar_view_agenda),
    DAY(R.string.calendar_view_day),
    WEEK(R.string.calendar_view_week),
    MONTH(R.string.calendar_view_month),
}
