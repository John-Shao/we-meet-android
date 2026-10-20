package com.we.meet.feature.assistant.aicall.model

/**
 * AOQ audio path. [EchoCancel] starts the SDK in VoIP mode, which enables the
 * platform/hardware AEC and keeps the assistant from hearing its own playback;
 * it is quieter. [Volume] uses media playback (louder) but with no hardware AEC
 * reference, so speaker output re-enters the microphone and can make the model
 * answer itself. WebRTC always runs its own AEC and ignores this setting.
 */
enum class AiCallAudioMode(val voip: Boolean) {
    EchoCancel(true),
    Volume(false);

    companion object {
        fun fromStored(value: String?): AiCallAudioMode =
            entries.firstOrNull { it.name == value } ?: EchoCancel
    }
}
