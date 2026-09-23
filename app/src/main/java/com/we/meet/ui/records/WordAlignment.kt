package com.we.meet.ui.records

import com.we.meet.data.api.dto.PlaybackAlignmentDto
import com.we.meet.data.api.dto.PlaybackWordDto
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

internal fun validatedWords(text: String, data: PlaybackAlignmentDto?): List<PlaybackWordDto> {
    if (data?.status != "available" || data.version != 1 || data.timeBasis != "segment_source" ||
        data.offsetUnit != "utf16" || (data.alignmentRevision ?: 0) < 1) return emptyList()
    val tokens = data.tokens ?: return emptyList()
    if (tokens.isEmpty() || tokens.size > 10_000) return emptyList()
    val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    if (hash != data.textSha256) return emptyList()
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    fun boundary(at: Int): Boolean = breaks.isBoundary(at) &&
        (at == 0 || at == text.length || (text[at] != '\u200d' && text[at - 1] != '\u200d'))
    fun ignorable(value: String) = value.all { char -> char.isWhitespace() || Character.getType(char) in setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt()) }
    var offset = 0
    var end = 0L
    for (token in tokens) {
        if (token.startOffset < offset || token.endOffset <= token.startOffset || token.endOffset > text.length ||
            token.startMs < end || token.endMs <= token.startMs || !boundary(token.startOffset) || !boundary(token.endOffset) ||
            !ignorable(text.substring(offset, token.startOffset))) return emptyList()
        offset = token.endOffset
        end = token.endMs
    }
    return if (ignorable(text.substring(offset))) tokens else emptyList()
}

internal fun activeWordIndex(tokens: List<PlaybackWordDto>, positionMs: Long?): Int {
    if (positionMs == null) return -1
    var low = 0
    var high = tokens.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (tokens[mid].startMs <= positionMs) low = mid + 1 else high = mid
    }
    return if (low > 0 && positionMs < tokens[low - 1].endMs) low - 1 else -1
}
