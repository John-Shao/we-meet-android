package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.history.AssistantHistoryRow
import org.json.JSONObject

/** Reserve positions before ASR finishes, since source transcription can arrive after the reply. */
internal class OmniTranscript(private val emit: (AssistantHistoryRow) -> Unit) {
    private val positions = linkedMapOf<String, Int>()
    private fun position(id: String) = positions.getOrPut(id) { positions.size }

    fun accept(event: JSONObject) {
        when (event.optString("type")) {
            "input_audio_buffer.committed" -> event.optString("item_id").takeIf { it.isNotBlank() }?.let(::position)
            "response.output_item.added", "conversation.item.created" -> {
                val item = event.optJSONObject("item") ?: return
                item.optString("id").takeIf { it.isNotBlank() }?.let(::position)
            }
            "conversation.item.input_audio_transcription.completed" -> emitFinal(event, "user", event.optString("transcript"))
            "response.audio_transcript.done" -> emitFinal(event, "assistant", event.optString("transcript"))
            "response.text.done" -> emitFinal(event, "assistant", event.optString("text"))
        }
    }

    private fun emitFinal(event: JSONObject, role: String, text: String) {
        val id = event.optString("item_id")
        if (id.isBlank() || text.isBlank() || text.length > 20000 || positions.size >= 2000 && id !in positions) return
        emit(AssistantHistoryRow("$id:${event.optInt("content_index")}", position(id), role, text))
    }
}
