package com.we.meet.ui.calendar

import java.time.ZoneId

/** Compact cross-region list for the mobile picker; always includes the active device/event zone. */
fun calendarTimezoneOptions(current: String): List<String> = (
    listOf(
        ZoneId.systemDefault().id,
        current,
        "UTC",
        "Asia/Shanghai",
        "Asia/Tokyo",
        "Asia/Singapore",
        "Asia/Kolkata",
        "Europe/London",
        "Europe/Paris",
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles",
        "Australia/Sydney",
        "Pacific/Auckland",
    )
).distinct().filter { runCatching { ZoneId.of(it) }.isSuccess }
