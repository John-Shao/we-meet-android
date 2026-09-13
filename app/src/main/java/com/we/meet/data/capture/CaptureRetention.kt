package com.we.meet.data.capture

import com.we.meet.data.api.dto.CaptureAudioRetentionDto
import java.time.Instant

/** Access expiry and verified deletion are distinct; malformed metadata never permits text audio. */
object CaptureRetention {
    const val MAX_TEMPORARY_MS = 24 * 60 * 60 * 1000L

    fun validate(value: CaptureAudioRetentionDto) {
        require(value.mode in setOf("media", "text"))
        require(value.cleanupStatus in setOf("not_started", "pending", "failed", "complete"))
        require(value.cleanupError.length <= 64)
        require((value.cleanupStatus == "complete") == (value.deletedAt != null))
        value.deletedAt?.let(Instant::parse)
        if (value.mode == "media") {
            require(value.temporaryUntil == null && value.retryUntil == null && !value.expired)
        } else {
            val hard = Instant.parse(requireNotNull(value.temporaryUntil))
            val retry = Instant.parse(requireNotNull(value.retryUntil))
            require(retry <= hard)
        }
    }

    fun canStartTranscription(value: CaptureAudioRetentionDto?, now: Instant = Instant.now()): Boolean =
        runCatching {
            validate(requireNotNull(value))
            value.mode == "text" && !value.expired && value.cleanupStatus == "not_started" && now < Instant.parse(value.retryUntil)
        }.getOrDefault(false)

    fun audioExpired(local: LocalCapture, now: Long = System.currentTimeMillis()): Boolean {
        if (local.create.retentionMode != "text") return false
        if (local.createdAt < 0 || local.createdAt > Long.MAX_VALUE - MAX_TEMPORARY_MS) return true
        val localDeadline = local.createdAt + MAX_TEMPORARY_MS
        val remote = local.remote ?: return now >= localDeadline
        return runCatching {
            val value = requireNotNull(remote.audioRetention)
            validate(value)
            value.mode != "text" || value.expired || value.cleanupStatus != "not_started" ||
                now >= minOf(localDeadline, Instant.parse(value.temporaryUntil).toEpochMilli())
        }.getOrDefault(true)
    }
}
