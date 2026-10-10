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

    @Test fun missingIdsAndEmptyEventsDoNotInventHistory() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript { rows += it }
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","delta":"partial"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","transcript":"no id"}"""))
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":""}"""))
        assertTrue(rows.isEmpty())
    }

    @Test fun streamingDeltasUpdateOneRowAndFinalTextIsAuthoritative() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript(rows::add)
        transcript.accept(JSONObject("""{"type":"input_audio_buffer.committed","item_id":"user"}"""))
        val first = JSONObject("""{"type":"response.audio_transcript.delta","response_id":"response","item_id":"ai","event_id":"d1","delta":"Hello"}""")
        transcript.accept(first); transcript.accept(first)
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"response","item_id":"ai","event_id":"d2","delta":" world"}"""))
        assertEquals(listOf("Hello", "Hello world"), rows.map { it.text })
        assertTrue(rows.all { it.isStreaming }); assertEquals(1, rows.map { it.id }.toSet().size)
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.done","item_id":"ai","transcript":"Hello world!"}"""))
        assertFalse(rows.last().isStreaming); assertEquals("Hello world!", rows.last().text)
        transcript.accept(first)
        assertEquals(3, rows.size)
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"user","transcript":"Hi"}"""))
        assertTrue(rows.last().order < rows.first().order)
    }

    @Test fun textAndAudioDoNotDoubleAppendAndContentSlotsRemainIndependent() {
        val rows = linkedMapOf<String, AssistantHistoryRow>()
        val transcript = OmniTranscript { rows[it.id] = it }
        transcript.accept(JSONObject("""{"type":"response.text.delta","item_id":"ai","content_index":0,"delta":"one"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","item_id":"ai","content_index":0,"delta":"one"}"""))
        transcript.accept(JSONObject("""{"type":"response.text.delta","item_id":"ai","content_index":1,"delta":"two"}"""))
        assertEquals(listOf("one", "two"), rows.values.map { it.text })
        transcript.accept(JSONObject("""{"type":"response.text.done","item_id":"ai","content_index":0,"text":"one!"}"""))
        assertEquals("one!", rows.getValue("ai:0").text)
        assertFalse(rows.getValue("ai:0").isStreaming)
        assertTrue(rows.getValue("ai:1").isStreaming)
    }

    @Test fun cancellationFinishesReceivedTextOnlyForItsResponseAndToolsStayHidden() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript(rows::add)
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"old","item_id":"a","delta":"received"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"new","item_id":"b","delta":"next"}"""))
        transcript.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"tool","item_id":"hidden","delta":"prelude"}"""), true)
        transcript.accept(JSONObject("""{"type":"response.done","response":{"id":"old","status":"cancelled"}}"""))
        assertEquals("received", rows.last().text); assertFalse(rows.last().isStreaming)
        assertTrue(rows.single { it.id == "b:0" }.isStreaming)
        assertTrue(rows.none { it.id == "hidden:0" })
    }

    @Test fun oversizedChunksDoNotGrowTheBufferOrOverwriteValidText() {
        val rows = mutableListOf<AssistantHistoryRow>()
        val transcript = OmniTranscript(rows::add)
        transcript.accept(JSONObject().put("type", "response.text.delta").put("item_id", "r").put("delta", "ok"))
        transcript.accept(JSONObject().put("type", "response.text.delta").put("item_id", "r").put("delta", "x".repeat(20000)))
        assertEquals(1, rows.size)
        transcript.accept(JSONObject("""{"type":"response.done","response":{"id":""}}"""))
        assertEquals("ok", rows.last().text); assertFalse(rows.last().isStreaming)
    }
}
