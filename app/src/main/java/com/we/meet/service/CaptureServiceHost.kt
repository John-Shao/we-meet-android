package com.we.meet.service

import android.content.Context
import com.we.meet.data.capture.AndroidCapturePcmSource
import com.we.meet.data.capture.CapturePcmSource
import com.we.meet.data.repository.CaptureRepository

/** Application-owned account/protocol dependencies; the service never initializes other modules. */
interface CaptureServiceHost {
    val captureAccount: String?
    val captureRepository: CaptureRepository
    fun openCaptureSource(context: Context, interrupted: () -> Unit): CapturePcmSource =
        AndroidCapturePcmSource.open(context).also { it.onInterrupted = interrupted }
}
