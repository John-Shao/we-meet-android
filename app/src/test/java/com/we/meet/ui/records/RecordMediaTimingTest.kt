package com.we.meet.ui.records

import com.we.meet.data.api.dto.*
import org.junit.Assert.*
import org.junit.Test

class RecordMediaTimingTest {
    private val record = RecordDto("record", "upload", "title", "2026-09-20T00:00:00Z", 1,
        RecordCapabilitiesDto(playMedia = true))
    @Test fun knownAndPreparedLengthsRemainSeparateFromPartialAudio() {
        assertNull(mediaDuration(record))
        assertEquals(5000L, mediaDuration(record, 5000))
        val partial = record.copy(mediaTiming = RecordMediaTimingDto(2000, 2000, "partial_audio"))
        assertNull(mediaDuration(partial))
        assertEquals(2000L, mediaDuration(partial.copy(mediaTiming = RecordMediaTimingDto(2000, 2000, "saved_audio"))))
        assertNull(mediaDuration(record.copy(capabilities = RecordCapabilitiesDto()), 5000))
    }
    @Test fun invalidDurationsAreUnknown() {
        for (value in listOf(null, 0L, -1L, 43_200_001L)) assertFalse(validMediaDuration(value))
    }
}
