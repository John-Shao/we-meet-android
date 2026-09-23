package com.we.meet.ui.records

import com.we.meet.data.api.dto.PlaybackAlignmentDto
import com.we.meet.data.api.dto.PlaybackWordDto
import com.we.meet.data.api.dto.decodePlaybackAlignment
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class WordAlignmentTest {
    @Test fun `malformed optional response cannot break reading originals`() {
        assertNull(decodePlaybackAlignment("bad"))
        assertNull(decodePlaybackAlignment(mapOf("status" to "available", "tokens" to listOf(null))))
        val raw = mapOf("status" to "available", "version" to 1.0, "alignment_revision" to 1.0,
            "time_basis" to "segment_source", "offset_unit" to "utf16", "text_sha256" to data().textSha256,
            "tokens" to words.map { mapOf("start_offset" to it.startOffset.toDouble(), "end_offset" to it.endOffset.toDouble(),
                "start_ms" to it.startMs.toDouble(), "end_ms" to it.endMs.toDouble()) })
        assertEquals(words, validatedWords(text, decodePlaybackAlignment(raw)))
        assertNull(decodePlaybackAlignment(raw + ("tokens" to listOf(mapOf("start_offset" to 0.5)))))
    }
    private val text = "我们，Hello 我们。"
    private val words = listOf(PlaybackWordDto(0, 2, 0, 400), PlaybackWordDto(3, 8, 500, 900), PlaybackWordDto(9, 11, 900, 1500))
    private fun data(value: String = text) = PlaybackAlignmentDto("available", 1, 1, "segment_source", "utf16",
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }, words)

    @Test fun `same half open clock rule as Web including silence and ended playback`() {
        val valid = validatedWords(text, data())
        assertEquals(words, valid)
        assertEquals(listOf(0, 0, -1, -1, 1, 2, -1, -1), listOf(0L, 399L, 400L, 499L, 500L, 900L, 1500L, -1L).map { activeWordIndex(valid, it) })
    }
    @Test fun `edited text unknown version and bad ranges fall back safely`() {
        assertTrue(validatedWords("edited", data()).isEmpty())
        assertTrue(validatedWords(text, data().copy(version = 2)).isEmpty())
        assertTrue(validatedWords(text, data().copy(tokens = words.drop(1))).isEmpty())
        assertTrue(validatedWords(text, data().copy(tokens = listOf(words.first(), words[1].copy(startMs = 300)))).isEmpty())
        assertTrue(validatedWords(text, null).isEmpty())
    }
    @Test fun `surrogate pairs and combining characters cannot be split`() {
        for (value in listOf("🙂", "e\u0301", "👩‍💻")) {
            assertTrue(validatedWords(value, data(value).copy(tokens = listOf(PlaybackWordDto(0, 1, 0, 500)))).isEmpty())
        }
    }
}
