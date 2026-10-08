package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.BuildConfig

/** Capture/preview and model submission are independent build-time frame-rate targets. */
internal object AiCallVideoConfig {
    const val localPreviewFps = BuildConfig.AI_CALL_LOCAL_PREVIEW_FPS
    const val modelUploadFps = BuildConfig.AI_CALL_MODEL_UPLOAD_FPS
    // Camera2 may require a higher hardware cadence than the desired preview rate.
    val captureFps = maxOf(15, localPreviewFps)
}
