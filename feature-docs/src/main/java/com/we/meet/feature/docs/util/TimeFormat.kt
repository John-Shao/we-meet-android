package com.we.meet.feature.docs.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "2026-09-06T10:00:00Z" → local "2026-09-06 18:00" (device zone). */
private val OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatIsoTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .format(OUTPUT_FORMAT.withLocale(Locale.getDefault()))
    }.getOrDefault("")
}
