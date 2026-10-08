package com.we.meet.feature.assistant.aicall.rtc

import org.json.JSONObject

/** Correlate the observed nonfatal rejection of an overlapping response.create. */
internal class OmniResponseCreation(private val now: () -> Long) {
    private val pending = linkedMapOf<String, Long>()

    fun sent(eventId: String) {
        prune()
        pending[eventId] = now()
        while (pending.size > 8) pending.remove(pending.keys.first())
    }

    fun created() {
        prune()
        pending.keys.firstOrNull()?.let(pending::remove)
    }

    fun rejectedRequest(error: JSONObject): String? {
        prune()
        // Measured on the actual provider DataChannel: this error has no code
        // or rejected client event ID. Do not generalize to other request errors.
        if (error.optString("type") != "invalid_request_error" || error.optString("code").isNotEmpty() ||
            error.optString("message") != "Conversation already has an active response" ||
            error.optString("param") !in listOf("", "response.create")) return null
        val explicit = error.optString("event_id")
        val id = if (explicit.isNotEmpty()) explicit.takeIf(pending::containsKey) else pending.keys.lastOrNull()
        if (id != null) pending.remove(id)
        return id
    }

    fun close() = pending.clear()
    private fun prune() { pending.entries.removeAll { now() - it.value > 30_000 } }
}
