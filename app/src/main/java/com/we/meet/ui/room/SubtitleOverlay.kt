package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme

/**
 * Bottom-anchored realtime subtitle strip — shows up to [MAX_VISIBLE_ROWS]
 * "rows", where consecutive segments from the same speaker collapse into
 * a single row (matches the Web layout's `transcriptionRows` grouping).
 *
 * Render is intentionally minimal: white text on a semi-translucent
 * black background, sized for readability over arbitrary participant
 * tiles. Translations are NOT shown here — Web's bilingual rendering
 * needs a `useTranslations` pipeline (S3.x follow-up) we haven't built
 * on App yet.
 *
 * The container is invisible (renders nothing) when the buffer is
 * empty, so a host enabling subtitles before anyone speaks doesn't
 * see a blank black bar pop in.
 */
@Composable
fun SubtitleOverlay(
    segments: List<SubtitleSegment>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    val rows = remember(segments) { groupRows(segments) }
    val visible = rows.takeLast(MAX_VISIBLE_ROWS)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding)
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(WeMeetTheme.extras.room.overlayScrim)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        visible.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.subtitle_speaker_prefix, row.speaker.ifBlank { "—" }),
                    color = WeMeetTheme.extras.room.subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private data class SubtitleRow(val speaker: String, val text: String)

/**
 * Collapse consecutive same-speaker segments into one row. The text is
 * concatenated with single spaces. Web does the same via the
 * `currentRow` accumulator in Subtitles.tsx.
 */
private fun groupRows(segments: List<SubtitleSegment>): List<SubtitleRow> {
    if (segments.isEmpty()) return emptyList()
    val rows = mutableListOf<SubtitleRow>()
    var currentSpeaker = ""
    val currentText = StringBuilder()
    fun flush() {
        if (currentText.isNotEmpty()) {
            rows.add(SubtitleRow(currentSpeaker, currentText.toString()))
        }
    }
    for (seg in segments) {
        if (seg.participantIdentity != currentSpeaker && currentText.isNotEmpty()) {
            flush()
            currentText.clear()
        }
        if (currentText.isNotEmpty()) currentText.append(' ')
        currentText.append(seg.text)
        currentSpeaker = seg.participantIdentity
        // Use the latest participant name we saw — handles renames mid-row.
        rows.lastOrNull()?.let { /* name lives in `speaker` of the next flush */ }
    }
    flush()
    // Replace speaker identity with the display name from the last
    // segment we observed for that identity.
    val identityToName = segments.associate { it.participantIdentity to it.participantName }
    return rows.map { it.copy(speaker = identityToName[it.speaker] ?: it.speaker) }
}

private const val MAX_VISIBLE_ROWS = 3
