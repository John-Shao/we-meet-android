package com.we.meet.feature.assistant.aicall.model

import com.we.meet.feature.assistant.history.AssistantHistoryRow
import org.junit.Assert.*
import org.junit.Test

class AiCallTranscriptTest {
    @Test fun streamUpdatesKeepTheTimestampAndCannotEnterTheNextCall() {
        val partial = AssistantHistoryRow("ai", 1, "assistant", "first", isStreaming = true)
        val first = AiCallUiState(transcriptSessionId = "call").withTranscript("call", partial, 1000)
        val completed = first.withTranscript("call", partial.copy(text = "first reply", isStreaming = false), 2000)
        assertEquals(1, completed.transcriptRows.size)
        assertEquals(1000L, completed.transcriptTimestamps["ai"])
        assertFalse(completed.transcriptRows.single().isStreaming)
        val next = completed.copy(transcriptSessionId = "next", transcriptRows = emptyList(), transcriptTimestamps = emptyMap())
        assertSame(next, next.withTranscript("call", partial.copy(text = "old delta")))
    }
    @Test fun currentPhotosNeedNoSavingAndCannotLeakFromPreviousCall() {
        val photo = AssistantHistoryRow("photo", 1, "user", "", photo = com.we.meet.feature.assistant.history.AssistantHistoryPhoto.Memory(byteArrayOf(1)))
        val state = AiCallUiState(transcriptSessionId = "call").withTranscript("call", photo)
            .withTranscript("call", row("question", 0, "look"))
        assertEquals(listOf("question", "photo"), state.transcriptRows.map { it.id })
        assertNotNull(state.copy(status = AiCallStatus.Ended).transcriptRows.last().photo)
        val next = state.copy(transcriptSessionId = "next", transcriptRows = emptyList(), transcriptTimestamps = emptyMap())
        assertSame(next, next.withTranscript("call", photo))
    }

    private fun row(id: String, order: Int, text: String) = AssistantHistoryRow(id, order, "user", text)

    @Test fun lateSourceAndDuplicateFinalsAreOrderedAndUpdated() {
        val state = AiCallUiState(transcriptSessionId = "call")
            .withTranscript("call", row("reply", 1, "answer"), receivedAt = 1_000)
            .withTranscript("call", row("source", 0, "question"), receivedAt = 2_000)
            .withTranscript("call", row("reply", 1, "corrected answer"), receivedAt = 9_000)
        assertEquals(listOf("source", "reply"), state.transcriptRows.map { it.id })
        assertEquals("corrected answer", state.transcriptRows.last().text)
        assertEquals(mapOf("reply" to 1_000L, "source" to 2_000L), state.transcriptTimestamps)
    }

    @Test fun transcriptNeedsNoHistoryRecordingAndSurvivesEnding() {
        val state = AiCallUiState(transcriptSessionId = "call")
            .withTranscript("call", row("source", 0, "question"))
        val ended = state.copy(transcriptSessionId = null, status = AiCallStatus.Ended)
        assertEquals(state.transcriptRows, ended.transcriptRows)
        assertEquals(state.transcriptTimestamps, ended.transcriptTimestamps)
        assertSame(ended, ended.withTranscript("call", row("late", 1, "late callback")))
    }

    @Test fun nextCallStartsEmptyAndRejectsOldCallbacks() {
        val previous = AiCallUiState(transcriptSessionId = "old")
            .withTranscript("old", row("source", 0, "old question"))
        val next = previous.copy(transcriptSessionId = "new", transcriptRows = emptyList(), transcriptTimestamps = emptyMap())
        assertTrue(next.transcriptTimestamps.isEmpty())
        assertSame(next, next.withTranscript("old", row("late", 1, "old answer")))
        assertEquals(listOf("new question"), next.withTranscript("new", row("new", 0, "new question")).transcriptRows.map { it.text })
    }
}
