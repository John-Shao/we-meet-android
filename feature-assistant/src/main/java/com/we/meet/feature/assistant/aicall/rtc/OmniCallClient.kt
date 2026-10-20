package com.we.meet.feature.assistant.aicall.rtc

import android.content.Intent
import com.we.meet.feature.assistant.aicall.model.AiCallAnswer

/** Shared call controls; each transport owns its media and preview lifecycle. */
interface OmniCallClient {
    val cameraAvailable: Boolean get() = true
    val cameraEnabled: Boolean?
    val screenShareAvailable: Boolean get() = false
    val screenSharing: Boolean get() = false
    fun publishCameraState() = Unit
    val cameraFront: Boolean
    suspend fun connect(exchange: suspend (String) -> AiCallAnswer)
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setOutputMuted(muted: Boolean)
    fun interrupt()
    suspend fun setCameraEnabled(enabled: Boolean)

    /**
     * Starts/stops publishing the display. [resultData] is the result Intent
     * from `MediaProjectionManager.createScreenCaptureIntent()` and is
     * consumed exactly once. [onSystemStop] fires when the user or the OS ends
     * the projection outside the app (system chip, lock screen, revocation).
     */
    suspend fun setScreenShareEnabled(
        enabled: Boolean,
        resultData: Intent? = null,
        onSystemStop: (() -> Unit)? = null,
        onVisibilityChanged: ((Boolean) -> Unit)? = null,
    ) {
        error("Screen sharing unavailable")
    }

    /**
     * Tells the model whether the video track now carries a screen. Also
     * removes camera/photo tools while sharing so the model analyzes the
     * screen instead of trying to capture the camera.
     */
    fun publishScreenShareState(sharing: Boolean, visible: Boolean = true) = Unit

    suspend fun flipCamera(): Boolean
    suspend fun capturePhoto(): ByteArray = error("Photo capture unavailable")

    /** A still of the current shared screen, used for explicit screen reading. */
    suspend fun captureScreenPhoto(): ByteArray = error("Screen photo unavailable")

    /** Insert the exact JPEG already sent for screen reading into the transcript. */
    fun displayScreenPhoto(jpeg: ByteArray) = Unit
    fun close()
}
