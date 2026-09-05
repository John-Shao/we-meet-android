package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.OnMediaOverlay
import com.we.meet.ui.theme.WeMeetTheme

/**
 * Bottom-anchored realtime subtitle strip — shows up to [MAX_VISIBLE_ROWS]
 * speaker turns. A turn only keeps its latest stable segment because this is
 * a live caption overlay, not transcript history. Each turn is also capped at
 * [MAX_LINES_PER_ROW] visual lines so long meetings can never cover the room.
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
            val speakerPrefix = stringResource(
                R.string.subtitle_speaker_prefix,
                row.speaker.ifBlank { "—" },
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = WeMeetTheme.extras.room.subtitleText,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    ) {
                        append(speakerPrefix)
                    }
                    append(row.text)
                },
                modifier = Modifier.fillMaxWidth(),
                color = OnMediaOverlay,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = MAX_LINES_PER_ROW,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal data class SubtitleRow(val speaker: String, val text: String)

/**
 * Collapse consecutive same-speaker segments into one turn, retaining only
 * that turn's latest non-blank segment. Keeping the full accumulated turn is
 * what previously allowed one speaker to grow this overlay to full-screen.
 */
internal fun groupRows(segments: List<SubtitleSegment>): List<SubtitleRow> {
    val rows = mutableListOf<SubtitleRow>()
    var currentIdentity: String? = null

    for (seg in segments) {
        val text = seg.text.trim()
        if (text.isEmpty()) continue

        val nextRow = SubtitleRow(
            speaker = seg.participantName.ifBlank { seg.participantIdentity },
            text = text,
        )
        if (seg.participantIdentity == currentIdentity && rows.isNotEmpty()) {
            // A subtitle turn is a live snapshot: replace its previous packet
            // instead of accumulating an ever-growing transcript paragraph.
            rows[rows.lastIndex] = nextRow
        } else {
            rows.add(nextRow)
            currentIdentity = seg.participantIdentity
        }
    }
    return rows
}

private const val MAX_VISIBLE_ROWS = 3
private const val MAX_LINES_PER_ROW = 2
