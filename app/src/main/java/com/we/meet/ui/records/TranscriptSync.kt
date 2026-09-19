package com.we.meet.ui.records

/**
 * Playback ↔ transcript coupling for the record reader.
 *
 * Mirrors the Web implementation (`transcriptSync.ts`) on purpose: both clients
 * derive the active row from the same rule, so a reader switching devices does
 * not get a different answer about which sentence is being spoken.
 */

/** A row that carries its own source window, in the recording's clock. */
internal data class TimedRow(val id: String, val startMs: Long, val endMs: Long?)

internal fun transcriptWindowTarget(rows: List<TimedRow>, positionMs: Long, anchorMs: Long, hasNext: Boolean): Long? {
    if (rows.isEmpty() || positionMs < 0 || positionMs == anchorMs) return null
    if (anchorMs > 0 && positionMs < rows.first().startMs) return positionMs
    if (hasNext && positionMs >= rows.last().startMs) return positionMs
    return null
}

/**
 * The row whose window contains [positionMs].
 *
 * Rows are in transcript order. A recording may have gaps — the manifest stores
 * them explicitly — in which case playback sits between two rows and no row is
 * active. Naming a neighbour there would mark text that is not being spoken.
 *
 * The window is half-open (`[startMs, endMs)`), so a boundary belongs to the row
 * that starts on it and exactly one row can ever be active.
 *
 * A row without an [TimedRow.endMs] is the last one and stays active to the end.
 */
internal fun activeRowId(rows: List<TimedRow>, positionMs: Long): String? {
    if (rows.isEmpty() || positionMs < 0L) return null
    var candidate: TimedRow? = null
    for (row in rows) {
        if (row.startMs > positionMs) break
        candidate = row
    }
    val found = candidate ?: return null
    val end = found.endMs ?: return found.id
    return if (positionMs < end) found.id else null
}

/** The last row that has already started, used to follow playback across gaps. */
internal fun nearestStartedRowId(rows: List<TimedRow>, positionMs: Long): String? {
    var candidate: String? = null
    for (row in rows) {
        if (row.startMs > positionMs) break
        candidate = row.id
    }
    return candidate
}
