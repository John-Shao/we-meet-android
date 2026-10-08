package com.we.meet.feature.assistant.aicall.rtc

/** A rejected AOQ video append does not invalidate the established session. */
internal object OmniMediaErrors {
    fun isImageBeforeAudio(type: String, code: String, message: String, eventId: String, param: String): Boolean =
        type == "invalid_request_error" && code.isEmpty() && eventId.isEmpty() && param.isEmpty() &&
            message == "Error append image before append audio."
}
