package com.we.meet.feature.docs.ui

import com.we.meet.feature.docs.util.formatIsoTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun formatsSearchTimestampsWithFractionalSecondsInDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("2026-09-08 22:33", formatIsoTime("2026-09-08T14:33:00.188117+00:00"))
            assertEquals("2026-09-08 22:33", formatIsoTime("2026-09-08T14:33:00Z"))
            assertEquals("", formatIsoTime("invalid"))
            assertEquals("", formatIsoTime(null))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
