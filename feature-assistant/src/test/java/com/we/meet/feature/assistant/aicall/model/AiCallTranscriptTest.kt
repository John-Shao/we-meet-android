package com.we.meet.feature.assistant.aicall.model

import com.we.meet.feature.assistant.history.AssistantHistoryRow
import org.junit.Assert.*
import org.junit.Test

class AiCallTranscriptTest {
    private fun row(id: String, order: Int, text: String) = AssistantHistoryRow(id, order, "user", text)

    @Test fun lateSourceAndDuplicateFinalsAreOrderedAndUpdated() {
        val state = AiCallUiState(transcriptSessionId = "call")
            .withTranscript("call", row("reply", 1, "answer"))
            .withTranscript("call", row("source", 0, "question"))
            .withTranscript("call", row("reply", 1, "corrected answer"))
        assertEquals(listOf("source", "reply"), state.transcriptRows.map { it.id })
        assertEquals("corrected answer", state.transcriptRows.last().text)
    }

    @Test fun transcriptNeedsNoHistoryRecordingAndSurvivesEnding() {
        val state = AiCallUiState(transcriptSessionId = "call")
            .withTranscript("call", row("source", 0, "question"))
        val ended = state.copy(transcriptSessionId = null, status = AiCallStatus.Ended)
        assertEquals(state.transcriptRows, ended.transcriptRows)
        assertSame(ended, ended.withTranscript("call", row("late", 1, "late callback")))
    }

    @Test fun nextCallStartsEmptyAndRejectsOldCallbacks() {
        val previous = AiCallUiState(transcriptSessionId = "old")
            .withTranscript("old", row("source", 0, "old question"))
        val next = previous.copy(transcriptSessionId = "new", transcriptRows = emptyList())
        assertSame(next, next.withTranscript("old", row("late", 1, "old answer")))
        assertEquals(listOf("new question"), next.withTranscript("new", row("new", 0, "new question")).transcriptRows.map { it.text })
    }
}
