package com.we.meet.feature.assistant.aicall.rtc

/** Distinguish a rejected best-effort cancel from a fatal session error. */
internal class OmniCancellation(private val now: () -> Long) {
    private val pending = linkedMapOf<String, Long>()

    fun sent(eventId: String) {
        prune()
        pending[eventId] = now()
        while (pending.size > 8) pending.remove(pending.keys.first())
    }

    fun recoverable(type: String, code: String, message: String, eventId: String, param: String): Boolean {
        prune()
        if (pending.isEmpty() || type != "invalid_request_error") return false
        if (param.isNotEmpty() && param != "response.cancel") return false
        // A provider may identify the rejected client event. Never consume an
        // error explicitly attributed to another request (e.g. session.update).
        if (eventId.isNotEmpty()) return pending.remove(eventId) != null
        val description = message.lowercase()
        val noResponse = code == "response_cancel_not_active" ||
            // Observed on Qwen's AOQ transport: no code, parameter or client
            // event ID, and the message does not contain the word "cancel".
            (code.isEmpty() && description == "conversation has none active response") ||
            ("cancel" in description && "response" in description &&
                listOf("no active", "no ongoing", "not active", "not in progress").any { it in description })
        if (param != "response.cancel" && !noResponse) return false
        pending.remove(pending.keys.first())
        return true
    }

    private fun prune() {
        val cutoff = now() - 30_000
        pending.entries.removeAll { it.value < cutoff }
    }
}
