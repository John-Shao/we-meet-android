package com.we.meet.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.we.meet.R
import com.we.meet.data.api.dto.RecordSpeakerTimelineDto
import com.we.meet.ui.theme.Dimens

/** All speakers use the same recognized extent. Silent gaps keep their width. */
@Composable
internal fun SpeakerTimeline(timeline: RecordSpeakerTimelineDto?, onSource: ((Long) -> Unit)? = null) {
    if (timeline == null) return // Old servers do not advertise a timeline.
    if (!timeline.isUsable()) {
        Text(stringResource(R.string.speaker_timeline_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    val extent = requireNotNull(timeline.extentMs)
    var expanded by remember(timeline) { mutableStateOf(false) }
    var page by remember(timeline) { mutableIntStateOf(0) }
    val foreground = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.speaker_timeline_basis, sourceTime(extent)), style = MaterialTheme.typography.bodySmall)
        // Exact proportional geometry; the list below supplies full-size TalkBack targets.
        Canvas(Modifier.fillMaxWidth().height(Dimens.MinTouchTarget).then(
            if (onSource == null) Modifier else Modifier.pointerInput(timeline, onSource) {
                detectTapGestures { point ->
                    if (size.width > 0) {
                        val at = point.x.toDouble() / size.width * extent
                        timeline.intervals.firstOrNull { at >= it.startMs && at < it.endMs }?.let { onSource(it.startMs) }
                    }
                }
            }
        )) {
            drawRect(background)
            timeline.intervals.forEach { span ->
                val left = (span.startMs.toDouble() / extent * size.width).toFloat()
                val width = ((span.endMs - span.startMs).toDouble() / extent * size.width).toFloat()
                drawRect(foreground, Offset(left, 0f), Size(width, size.height))
            }
        }
        if (timeline.status == "partial") Text(stringResource(R.string.speaker_timeline_partial), style = MaterialTheme.typography.bodySmall)
        if (onSource == null) Text(stringResource(R.string.speaker_timeline_read_only), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.speaker_timeline_hide else R.string.speaker_timeline_intervals, timeline.intervals.size))
        }
        if (expanded) {
            timeline.intervals.drop(page * 10).take(10).forEach { span ->
                val label = stringResource(R.string.speaker_timeline_seek, sourceTime(span.startMs), sourceTime(span.endMs))
                if (onSource == null) Text("${sourceTime(span.startMs)} – ${sourceTime(span.endMs)}")
                else TextButton(onClick = { onSource(span.startMs) }) { Text(label) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                if (page > 0) TextButton(onClick = { page-- }) { Text(stringResource(R.string.records_previous)) }
                if ((page + 1) * 10 < timeline.intervals.size) TextButton(onClick = { page++ }) { Text(stringResource(R.string.records_next)) }
            }
        }
    }
}
