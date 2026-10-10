package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.history.AssistantHistoryRow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmniTranscriptTest {
    @Test fun photosReserveTheirOwnPositionsBeforeReplyAndLateUserTranscription() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript { rows += it }
        transcript.accept(JSONObject("""{"type":"input_audio_buffer.committed","item_id":"user-1"}"""))
        val jpeg = byteArrayOf(1, 2, 3)
        transcript.photo(jpeg)
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","item_id":"reply-1","transcript":"A red cup"}"""))
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"user-1","transcript":"What is this?"}"""))
        val ordered = rows.sortedBy { it.order }
        assertEquals(listOf("What is this?", "", "A red cup"), ordered.map { it.text })
        val photo = ordered[1].photo as com.we.meet.feature.assistant.history.AssistantHistoryPhoto.Memory
        jpeg[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), photo.jpeg)
        transcript.photo(byteArrayOf(4))
        assertEquals(4, rows.map { it.id }.toSet().size)
    }

    @Test fun lateSourceTranscriptionStaysBeforeItsReplyAndRepeatedFinalReplacesTheSameRow() {
        val rows = linkedMapOf<String, AssistantHistoryRow>()
        val transcript = OmniTranscript { rows[it.id] = it }
        transcript.accept(JSONObject("""{"type":"input_audio_buffer.committed","item_id":"user-1"}"""))
        transcript.accept(JSONObject("""{"type":"response.output_item.added","item":{"id":"reply-1"}}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","item_id":"reply-1","transcript":"你好"}"""))
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"user-1","transcript":"Hello"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","item_id":"reply-1","transcript":"你好！"}"""))
        val ordered = rows.values.sortedBy { it.order }
        assertEquals(listOf("user", "assistant"), ordered.map { it.role })
        assertEquals(listOf("Hello", "你好！"), ordered.map { it.text })
    }

    @Test fun incompleteAndMissingIdEventsDoNotInventHistory() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript { rows += it }
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","item_id":"r","delta":"partial"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","transcript":"no id"}"""))
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":""}"""))
        assertTrue(rows.isEmpty())
    }
}
