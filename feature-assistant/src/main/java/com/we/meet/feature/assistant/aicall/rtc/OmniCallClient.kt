package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.AiCallAnswer

/** Shared call controls; each transport owns its media and preview lifecycle. */
interface OmniCallClient {
    val cameraFront: Boolean
    suspend fun connect(exchange: suspend (String) -> AiCallAnswer)
    fun setMicrophoneEnabled(enabled: Boolean)
    fun setOutputMuted(muted: Boolean)
    fun interrupt()
    fun setCameraEnabled(enabled: Boolean)
    suspend fun flipCamera(): Boolean
    fun close()
}
