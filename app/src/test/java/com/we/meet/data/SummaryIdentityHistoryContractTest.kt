package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.RecordSummaryVersionDto
import org.junit.Assert.*
import org.junit.Test

class SummaryIdentityHistoryContractTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RecordSummaryVersionDto::class.java)
    private val legacy = """{"id":"version","stage":"final","input_snapshot_id":"old-snapshot","input_revision":2,"is_current":false,"created_at":"2026-10-11T00:00:00Z","delivery_status":"complete","content":{"overview":"Frozen minutes","decisions":[],"chapters":[],"action_items":[],"open_questions":[]}}"""

    @Test fun olderServersKeepHistoricalContentReadableWithoutIdentityNotice() {
        val result = adapter.fromJson(legacy)!!
        assertFalse(result.identityUpdated)
        assertEquals("Frozen minutes", result.content.overview)
        assertEquals("old-snapshot", result.inputSnapshotId)
    }

    @Test fun explicitIdentityMetadataDoesNotReplaceFrozenContentOrReference() {
        for (updated in listOf(true, false)) {
            val result = adapter.fromJson(legacy.replace("\"is_current\":false", "\"is_current\":false,\"identity_updated\":$updated"))!!
            assertEquals(updated, result.identityUpdated)
            assertEquals("Frozen minutes", result.content.overview)
            assertEquals("old-snapshot", result.inputSnapshotId)
            assertEquals(2, result.inputRevision)
        }
    }
}
