package com.we.meet.data.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generated at the explicit start action; resuming keeps the original capture name. */
fun defaultCaptureTitle(prefix: String, time: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
    prefix + SimpleDateFormat("yyMMdd-HHmmss", Locale.ROOT).apply { timeZone = zone }.format(Date(time))
