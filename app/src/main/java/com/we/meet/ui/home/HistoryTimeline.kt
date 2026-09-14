package com.we.meet.ui.home

import com.we.meet.data.api.dto.RecordDto
import com.we.meet.data.history.HistoryEntry
import java.time.OffsetDateTime

/** Record IDs are not room IDs: retain the type through rendering and navigation. */
internal sealed interface HistoryTimelineItem {
    val key: String
    val sortTime: Long
    data class Meeting(val entry: HistoryEntry) : HistoryTimelineItem {
        override val key = "meeting:${entry.roomId}"
        override val sortTime = maxOf(entry.firstJoinedAtMs, entry.lastLeftAtMs ?: 0, entry.createdAtMs)
    }
    data class Recording(val record: RecordDto, val originMs: Long) : HistoryTimelineItem {
        override val key = "recording:${record.id}"
        override val sortTime = originMs
    }
}

internal fun historyTimeline(meetings: List<HistoryEntry>, recordings: List<RecordDto>): List<HistoryTimelineItem> {
    val audio = recordings.filter { it.sourceType == "audio_recording" && !it.isOngoing }.map { record ->
        val time = runCatching { OffsetDateTime.parse(record.originAt).toInstant().toEpochMilli() }.getOrDefault(0L)
        HistoryTimelineItem.Recording(record, time)
    }
    return (meetings.map { HistoryTimelineItem.Meeting(it) } + audio)
        .distinctBy { it.key }
        .sortedWith(compareByDescending<HistoryTimelineItem> { it.sortTime }.thenBy { it.key })
}
