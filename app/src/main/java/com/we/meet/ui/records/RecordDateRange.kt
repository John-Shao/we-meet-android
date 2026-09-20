package com.we.meet.ui.records

import java.time.LocalDate
import java.time.ZoneId

/** Local calendar days, with an exclusive next-day bound rather than a fixed 24h. */
internal fun recordDateRange(from: String, through: String, zone: ZoneId = ZoneId.systemDefault()): Pair<String?, String?> {
    fun parse(value: String): LocalDate {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
        return LocalDate.parse(value).also { require(it.year in 1000..9998) }
    }
    val start = from.takeIf { it.isNotEmpty() }?.let(::parse)
    val end = through.takeIf { it.isNotEmpty() }?.let(::parse)
    require(start == null || end == null || start <= end)
    return start?.atStartOfDay(zone)?.toInstant()?.toString() to
        end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toString()
}
