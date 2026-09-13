package com.we.meet.data

import com.we.meet.data.api.dto.CaptureAudioRetentionDto
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.api.dto.CreateCaptureDto
import com.we.meet.data.capture.CaptureRetention
import com.we.meet.data.capture.LocalCapture
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CaptureRetentionTest {
    private val start = Instant.parse("2026-09-13T10:00:00Z")
    private val hard = start.plusSeconds(86400)
    private val retry = start.plusSeconds(1800)
    private val retention = CaptureAudioRetentionDto("text", hard.toString(), retry.toString(), false, "not_started", "", null)
    private fun local(value: CaptureAudioRetentionDto? = retention) = LocalCapture("local", start.toEpochMilli(), "key",
        CreateCaptureDto("device", "lease", "fixture", "text"),
        remote = CaptureDto("capture", "record", "device", "recording", 1, start.toString(), mediaStatus = "uploading", lastAckedSequence = 0, audioRetention = value))

    @Test fun runningAudioMayOutliveRetryStartWindowButNeverHardDeadline() {
        assertTrue(CaptureRetention.canStartTranscription(retention, retry.minusMillis(1)))
        assertFalse(CaptureRetention.canStartTranscription(retention, retry))
        assertFalse(CaptureRetention.audioExpired(local(), retry.toEpochMilli()))
        assertTrue(CaptureRetention.audioExpired(local(), hard.toEpochMilli()))
    }

    @Test fun cleanupEnrollmentAndMalformedStateFailClosed() {
        for (state in listOf("pending", "failed", "complete", "unknown")) {
            val value = retention.copy(cleanupStatus = state)
            assertFalse(CaptureRetention.canStartTranscription(value, start))
            assertTrue(CaptureRetention.audioExpired(local(value), start.toEpochMilli()))
        }
        assertFalse(CaptureRetention.canStartTranscription(null, start))
        assertTrue(CaptureRetention.audioExpired(local(null), start.toEpochMilli()))
    }

    @Test fun localClockBoundCannotBeExtendedByLaterServerDeadline() {
        val value = retention.copy(temporaryUntil = hard.plusSeconds(86400).toString())
        assertTrue(CaptureRetention.audioExpired(local(value), hard.toEpochMilli()))
        assertTrue(CaptureRetention.audioExpired(local().copy(createdAt = Long.MAX_VALUE), start.toEpochMilli()))
    }

    @Test fun mediaRetentionDoesNotExpireUnderTextPolicy() {
        val value = local().copy(create = local().create.copy(retentionMode = "media"))
        assertFalse(CaptureRetention.audioExpired(value, Long.MAX_VALUE))
        CaptureRetention.validate(retention.copy(mode = "media", temporaryUntil = null, retryUntil = null))
    }

    @Test fun retryDeadlineCannotExceedHardDeadline() {
        assertTrue(runCatching { CaptureRetention.validate(retention.copy(retryUntil = hard.plusSeconds(1).toString())) }.isFailure)
        assertTrue(runCatching { CaptureRetention.validate(retention.copy(temporaryUntil = "2026-09-13")) }.isFailure)
    }

    @Test fun deletedRequiresVerifiedCompletionTimestamp() {
        assertTrue(runCatching { CaptureRetention.validate(retention.copy(cleanupStatus = "complete")) }.isFailure)
        assertTrue(runCatching { CaptureRetention.validate(retention.copy(deletedAt = hard.toString())) }.isFailure)
        CaptureRetention.validate(retention.copy(cleanupStatus = "complete", deletedAt = hard.toString()))
    }
}
