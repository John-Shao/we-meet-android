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
