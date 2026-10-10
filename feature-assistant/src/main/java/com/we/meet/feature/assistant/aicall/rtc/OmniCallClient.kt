package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.AiCallAnswer

/** Shared call controls; each transport owns its media and preview lifecycle. */
interface OmniCallClient {
    val cameraAvailable: Boolean get() = true
    val cameraEnabled: Boolean?
    fun publishCameraState() = Unit
    val cameraFront: Boolean
    suspend fun connect(exchange: suspend (String) -> AiCallAnswer)
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setOutputMuted(muted: Boolean)
    fun interrupt()
    suspend fun setCameraEnabled(enabled: Boolean)
    suspend fun flipCamera(): Boolean
    suspend fun capturePhoto(): ByteArray = error("Photo capture unavailable")
    fun close()
}
