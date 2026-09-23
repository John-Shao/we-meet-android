@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.we.meet.ui.records

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.we.meet.data.api.dto.PlaybackWordDto

/** Selection stays native; short taps only seek within an actual glyph box. */
@Composable
internal fun WordPlaybackText(
    text: String,
    words: List<PlaybackWordDto>,
    activeIndex: Int,
    query: String,
    following: Boolean,
    onBrowse: () -> Unit,
    onSeek: ((Long) -> Unit)?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val colors = MaterialTheme.colorScheme
    val annotated = remember(text, words, activeIndex, query, colors) {
        buildAnnotatedString {
            val matches = highlightMatches(text, query, colors.primaryContainer, colors.onPrimaryContainer)
            append(matches)
            words.getOrNull(activeIndex)?.let { word ->
                addStyle(SpanStyle(background = primary, color = onPrimary), word.startOffset, word.endOffset)
                matches.spanStyles.forEach { match ->
                    val start = maxOf(match.start, word.startOffset)
                    val end = minOf(match.end, word.endOffset)
                    if (start < end) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                }
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val requester = remember { BringIntoViewRequester() }
    val latestSeek by rememberUpdatedState(onSeek)
    val latestBrowse by rememberUpdatedState(onBrowse)
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(activeIndex, following, layout?.size, touching) {
        val word = words.getOrNull(activeIndex)
        val result = layout
        if (following && !touching && word != null && result != null) requester.bringIntoView(result.getBoundingBox(word.startOffset))
    }
    SelectionContainer {
        Text(annotated, style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layout = it },
            modifier = Modifier.bringIntoViewRequester(requester).pointerInput(text, words) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touching = true
                    try {
                        val up = withTimeoutOrNull(minOf(450L, viewConfiguration.longPressTimeoutMillis)) {
                            waitForUpOrCancellation()
                        }
                        if (up == null || up.uptimeMillis - down.uptimeMillis >= 450 ||
                            (up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            latestBrowse()
                        } else {
                            val result = layout
                            if (result != null && text.isNotEmpty()) {
                                val offset = result.getOffsetForPosition(up.position)
                                if (offset < text.length && result.getBoundingBox(offset).contains(up.position)) {
                                    words.firstOrNull { offset >= it.startOffset && offset < it.endOffset }?.let { latestSeek?.invoke(it.startMs) }
                                }
                            }
                        }
                    } finally { touching = false }
                }
            })
    }
}
