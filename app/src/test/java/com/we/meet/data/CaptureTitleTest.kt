package com.we.meet.data

import com.we.meet.data.capture.defaultCaptureTitle
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTitleTest {
    @Test fun titleMatchesRequestedPatternInDeviceTimezone() {
        assertEquals("新录音-260914-220512", defaultCaptureTitle("新录音-",
            Instant.parse("2026-09-14T14:05:12Z").toEpochMilli(), TimeZone.getTimeZone("Asia/Shanghai")))
    }

    @Test fun dateRollsOverAtLocalMidnight() {
        assertEquals("New recording-260915-000003", defaultCaptureTitle("New recording-",
            Instant.parse("2026-09-14T16:00:03Z").toEpochMilli(), TimeZone.getTimeZone("Asia/Shanghai")))
    }
}
