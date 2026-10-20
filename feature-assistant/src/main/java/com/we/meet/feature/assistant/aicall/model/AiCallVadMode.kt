package com.we.meet.feature.assistant.aicall.model

/** Persist wire values so each new call uses the same VAD choice on both transports. */
enum class AiCallVadMode(val wireValue: String) {
    Server("server_vad"),
    Semantic("semantic_vad");

    companion object {
        fun fromStored(value: String?): AiCallVadMode =
            entries.firstOrNull { it.wireValue == value } ?: Server
    }
}

/**
 * The WebRTC realtime gateway answers the initial `session.update` with
 * `invalid_request_error` / `Cannot change turn_detection while input audio is pending`
 * whenever the requested detector differs from the default one. The call then fails
 * before `session.updated`. AOQ accepts the same payload, so the stored choice is kept
 * for AOQ and only WebRTC falls back to the acoustic detector until the gateway allows
 * the switch. Remove this mapping once the provider accepts it over WebRTC.
 */
fun AiCallVadMode.effectiveFor(transport: AiCallTransport): AiCallVadMode =
    if (transport == AiCallTransport.WebRTC && this == AiCallVadMode.Semantic) AiCallVadMode.Server else this
