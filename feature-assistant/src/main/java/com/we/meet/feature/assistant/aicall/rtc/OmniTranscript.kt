package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.history.AssistantHistoryRow
import com.we.meet.feature.assistant.history.AssistantHistoryPhoto
import org.json.JSONObject

/** Reserve positions before ASR finishes, since source transcription can arrive after the reply. */
internal class OmniTranscript(private val emit: (AssistantHistoryRow) -> Unit) {
    private val positions = linkedMapOf<String, Int>()
    private fun position(id: String) = positions.getOrPut(id) { positions.size }
    private data class Stream(val item: String, val response: String, val family: String,
        val text: StringBuilder = StringBuilder(), val events: MutableSet<String> = mutableSetOf())
    private val streams = linkedMapOf<String, Stream>()
    private val completed = mutableSetOf<String>()

    private fun key(event: JSONObject): String? {
        val id = event.optString("item_id")
        if (id.isBlank() || positions.size >= 2000 && id !in positions) return null
        return "$id:${event.optInt("content_index")}"
    }

    /** Reserve a slot before model continuation, so late user ASR still precedes the photo. */
    fun photo(jpeg: ByteArray) {
        if (positions.size >= 2000) return
        val id = "photo:${java.util.UUID.randomUUID()}"
        emit(AssistantHistoryRow(id, position(id), "user", "", photo = AssistantHistoryPhoto.Memory(jpeg)))
    }

    fun accept(event: JSONObject, suppressAssistant: Boolean = false) {
        when (event.optString("type")) {
            "input_audio_buffer.committed" -> event.optString("item_id").takeIf { it.isNotBlank() }?.let(::position)
            "response.output_item.added", "conversation.item.created" -> {
                val item = event.optJSONObject("item") ?: return
                item.optString("id").takeIf { it.isNotBlank() }?.let(::position)
            }
            "conversation.item.input_audio_transcription.completed" -> emitFinal(event, "user", event.optString("transcript"))
            "response.audio_transcript.delta", "response.text.delta" -> if (!suppressAssistant) emitDelta(event)
            "response.audio_transcript.done" -> if (!suppressAssistant) emitFinal(event, "assistant", event.optString("transcript"))
            "response.text.done" -> if (!suppressAssistant) emitFinal(event, "assistant", event.optString("text"))
            "response.done" -> {
                val response = event.optJSONObject("response")?.optString("id").orEmpty()
                streams.filterValues { it.response == response }.keys.toList().forEach { finish(it) }
            }
        }
    }

    private fun emitDelta(event: JSONObject) {
        val key = key(event) ?: return
        if (key in completed) return
        val delta = event.optString("delta")
        if (delta.isEmpty() || delta.length > 20000) return
        val family = event.optString("type").removeSuffix(".delta")
        val stream = streams.getOrPut(key) { Stream(event.optString("item_id"), event.optString("response_id"), family) }
        // Some providers expose audio and text for the same content slot.
        // Follow one stream rather than append the same answer twice.
        if (stream.family != family || stream.text.length + delta.length > 20000) return
        val eventId = event.optString("event_id")
        if (eventId.isNotEmpty() && !stream.events.add(eventId)) return
        stream.text.append(delta)
        emit(AssistantHistoryRow(key, position(stream.item), "assistant", stream.text.toString(), isStreaming = true))
    }

    private fun finish(key: String) {
        val stream = streams.remove(key) ?: return
        completed.add(key)
        if (stream.text.isNotBlank()) emit(AssistantHistoryRow(key, position(stream.item), "assistant", stream.text.toString()))
    }

    private fun emitFinal(event: JSONObject, role: String, text: String) {
        val key = key(event) ?: return
        if (text.isBlank()) { if (role == "assistant") finish(key); return }
        if (text.length > 20000) return
        streams.remove(key)
        completed.add(key)
        emit(AssistantHistoryRow(key, position(event.optString("item_id")), role, text))
    }
}
