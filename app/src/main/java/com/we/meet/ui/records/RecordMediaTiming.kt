package com.we.meet.ui.records

import com.we.meet.data.api.dto.RecordDto

internal fun validMediaDuration(value: Long?) = value != null && value in 1L..43_200_000L

internal fun mediaDuration(record: RecordDto, playerDuration: Long? = null): Long? {
    if (record.sourceType == "upload" && record.capabilities.playMedia && validMediaDuration(playerDuration)) return playerDuration
    val timing = record.mediaTiming ?: return null
    return timing.durationMs.takeIf { timing.basis in listOf("original_audio", "saved_audio") && validMediaDuration(it) }
}
