package com.we.meet.ui.home

import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.history.HistoryEntry
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryTimelineTest {
    private fun recording(id: String, time: String = "2026-09-14T08:00:00Z") = RecordDto(
        id, "audio_recording", "Recording", time, 1,
    )
    private fun meeting(id: String, time: String) = HistoryEntry(
        id, "Meeting", "room-slug", null, Instant.parse(time).toEpochMilli(), 0, null, emptyList(),
    )

    @Test fun recordingsInterleaveWithMeetingsAcrossTimeZonesAndKeepDistinctDestinations() {
        val rows = historyTimeline(
            listOf(meeting("same-id", "2026-09-14T08:30:00Z")),
            listOf(recording("same-id", "2026-09-14T17:00:00+08:00"), recording("older")),
        )
        assertEquals(listOf("recording:same-id", "meeting:same-id", "recording:older"), rows.map { it.key })
        assertEquals("same-id", (rows.first() as HistoryTimelineItem.Recording).record.id)
        assertEquals("same-id", (rows[1] as HistoryTimelineItem.Meeting).entry.roomId)
    }

    @Test fun onlyCompletedAudioAppearsWithoutWaitingForSummaryOrTranscript() {
        val rows = historyTimeline(emptyList(), listOf(
            recording("saved"), recording("uploading").copy(isOngoing = true),
            recording("online").copy(sourceType = "meeting"), recording("import").copy(sourceType = "upload"),
        ))
        assertEquals(listOf("recording:saved"), rows.map { it.key })
        assertFalse((rows.single() as HistoryTimelineItem.Recording).record.hasSummary)
    }

    @Test fun pageOverlapDoesNotDuplicateRecordingsAndBadDatesDoNotJumpToToday() {
        val row = recording("saved")
        val rows = historyTimeline(emptyList(), listOf(row, row, recording("bad", "invalid")))
        assertEquals(listOf("recording:saved", "recording:bad"), rows.map { it.key })
        assertEquals(0L, rows.last().sortTime)
    }

    @Test fun meetingsKeepExistingLastVisitOrdering() {
        val old = meeting("visited", "2026-09-01T00:00:00Z").copy(lastLeftAtMs = Instant.parse("2026-09-14T09:00:00Z").toEpochMilli())
        assertEquals("meeting:visited", historyTimeline(listOf(old), listOf(recording("saved"))).first().key)
    }
}
