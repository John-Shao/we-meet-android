package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.*
import org.junit.Assert.*
import org.junit.Test

class SpeakerTimelineTest {
    private val timeline = RecordSpeakerTimelineDto("recognized_extent", "available", extentMs = 7000,
        intervals = listOf(RecordSpeechIntervalDto(0, 1000), RecordSpeechIntervalDto(4000, 6000)))
    @Test fun acceptsCompleteAndPartialIntervals() {
        assertTrue(timeline.isUsable())
        assertTrue(timeline.copy(status = "partial").isUsable())
    }
    @Test fun rejectsUntrustedOffsets() {
        listOf(
            timeline.copy(extentMs = 0), timeline.copy(extentMs = null),
            timeline.copy(status = "unavailable"), timeline.copy(basis = "media_duration"),
            timeline.copy(intervals = emptyList()),
            timeline.copy(intervals = listOf(RecordSpeechIntervalDto(-1, 1000))),
            timeline.copy(intervals = listOf(RecordSpeechIntervalDto(0, 8000))),
            timeline.copy(intervals = listOf(RecordSpeechIntervalDto(0, 0))),
            timeline.copy(intervals = listOf(RecordSpeechIntervalDto(0, 5000), RecordSpeechIntervalDto(4000, 6000))),
            timeline.copy(intervals = List(1001) { RecordSpeechIntervalDto(it * 2L, it * 2L + 1) }),
        ).forEach { assertFalse(it.toString(), it.isUsable()) }
    }
    @Test fun readsWireContractAndDefaultsOldServersToAbsent() {
        val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(RecordSpeakerActivityDto::class.java)
        val old = """{"basis":"recognized_speaker_time","status":"available","duration_ms":1000,"share_percent":50} """
        assertNull(adapter.fromJson(old)!!.timeline)
        val wire = old.trim().dropLast(1) + """, "timeline":{"basis":"recognized_extent","status":"partial","extent_ms":7000,"intervals":[{"start_ms":4000,"end_ms":6000}]}}"""
        val read = adapter.fromJson(wire)!!.timeline!!
        assertTrue(read.isUsable())
        assertEquals(4000L, read.intervals.single().startMs)
    }
}
