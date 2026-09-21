package com.we.meet.ui.records

import com.we.meet.R
import com.we.meet.data.api.dto.CaptureAsrJobDto

internal fun captureAsrNoSpeech(job: CaptureAsrJobDto): Boolean =
    job.status == "incomplete" && job.errorCode == "no_speech_detected" && job.finalCount == 0

internal fun captureAsrStatus(job: CaptureAsrJobDto): Int =
    if (captureAsrNoSpeech(job)) R.string.capture_asr_no_speech
    else when (job.status) {
        "queued" -> R.string.capture_asr_queued
        "running" -> R.string.capture_asr_running
        "succeeded" -> R.string.capture_asr_succeeded
        "incomplete" -> R.string.capture_asr_incomplete
        "canceled" -> R.string.capture_asr_canceled
        else -> R.string.capture_asr_read_error
    }
