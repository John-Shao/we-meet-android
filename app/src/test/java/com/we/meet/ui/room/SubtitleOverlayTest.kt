package com.we.meet.ui.room

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOverlayTest {

    @Test
    fun `same speaker turn keeps only the latest segment`() {
        val rows = groupRows(
            listOf(
                segment("1", "w006", "W006", "old sentence"),
                segment("2", "w006", "W006", "latest sentence"),
            ),
        )

        assertEquals(listOf(SubtitleRow("W006", "latest sentence")), rows)
    }

    @Test
    fun `speaker change starts a new turn`() {
        val rows = groupRows(
            listOf(
                segment("1", "w006", "W006", "first"),
                segment("2", "w009", "W009", "second"),
                segment("3", "w006", "W006", "third"),
            ),
        )

        assertEquals(
            listOf(
                SubtitleRow("W006", "first"),
                SubtitleRow("W009", "second"),
                SubtitleRow("W006", "third"),
            ),
            rows,
        )
    }

    @Test
    fun `blank segments do not create empty turns`() {
        val rows = groupRows(
            listOf(
                segment("1", "w006", "W006", "  "),
                segment("2", "w006", "W006", " visible "),
            ),
        )

        assertEquals(listOf(SubtitleRow("W006", "visible")), rows)
    }

    private fun segment(
        id: String,
        identity: String,
        name: String,
        text: String,
    ) = SubtitleSegment(
        id = id,
        participantIdentity = identity,
        participantName = name,
        text = text,
        timestamp = 0L,
    )
}
