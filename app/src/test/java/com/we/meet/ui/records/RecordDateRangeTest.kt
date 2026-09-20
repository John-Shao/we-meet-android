package com.we.meet.ui.records

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordDateRangeTest {
    @Test fun localDayIncludesEndAndHandlesDaylightSaving() {
        val range = recordDateRange("2026-09-20", "2026-09-20", ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-09-19T16:00:00Z" to "2026-09-20T16:00:00Z", range)
        val dst = recordDateRange("2026-03-08", "2026-03-08", ZoneId.of("America/New_York"))
        assertEquals(23, Duration.between(Instant.parse(dst.first), Instant.parse(dst.second)).toHours())
    }
    @Test fun emptyAndOneSidedDatesAreValid() {
        assertEquals(null to null, recordDateRange("", ""))
        assertNull(recordDateRange("", "2026-09-20").first)
    }
    @Test fun invalidAndReversedDatesAreRejected() {
        listOf("2026-02-30" to "", "2026-9-2" to "", "2026-09-21" to "2026-09-20").forEach {
            assertTrue(runCatching { recordDateRange(it.first, it.second) }.isFailure)
        }
    }
}
