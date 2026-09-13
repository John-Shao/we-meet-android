package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.MeetingSharingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingSharingRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MeetingSharingCoordinatorTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "sharing-${UUID.randomUUID()}"
    private val record = UUID.randomUUID().toString()
    private var store = MeetingIntentStore.open(context, viewer) { viewer }
    private val input = SummaryShareRequestDto(listOf(UUID.randomUUID().toString()), "grant", "a".repeat(64))
    private val api = Fixture()
    private fun coordinator() = MeetingSharingCoordinator(viewer, store, MeetingSharingRepository(api) { viewer })
    @After fun close() { store.close() }
    @Test fun unknownGrantSurvivesRestartAndReplayCannotRegrantAfterRevocation() = runBlocking {
        api.status = 503
        assertTrue(runCatching { coordinator().apply(record, input) }.isFailure)
        val frozen = coordinator().pending(record)
        store.close(); store = MeetingIntentStore.open(context, viewer) { viewer }
        assertEquals(frozen, coordinator().pending(record)); assertEquals(1, api.sent.size)
        api.granted = false; api.status = 200
        coordinator().apply(record, input.copy(userIds = listOf(record), operation = "revoke"))
        assertEquals(api.sent.first(), api.sent.last()); assertEquals(1, api.applied.size); assertFalse(api.granted)
        assertNull(coordinator().pending(record))
    }
    @Test fun malformedReceiptAndPermissionLossPreserveOriginalConfirmedSelection() = runBlocking {
        api.invalid = true
        assertTrue(runCatching { coordinator().apply(record, input) }.isFailure)
        val frozen = coordinator().pending(record); assertNotNull(frozen)
        api.invalid = false; api.status = 403
        assertTrue(runCatching { coordinator().apply(record, input) }.isFailure)
        assertEquals(frozen, coordinator().pending(record))
        api.status = 200; coordinator().apply(record, input)
        assertEquals(1, api.sent.distinct().size)
    }
    @Test fun invalidInputAndDefinitiveConflictDoNotAffectOtherKinds() = runBlocking {
        val other = store.getOrCreate(MeetingIntentKind.DOCUMENT_EXPORT, record, "{}")
        assertTrue(runCatching { coordinator().apply(record, input.copy(userIds = emptyList())) }.isFailure)
        assertTrue(api.sent.isEmpty()); assertNull(coordinator().pending(record))
        api.status = 409
        assertTrue(runCatching { coordinator().apply(record, input) }.isFailure)
        assertNull(coordinator().pending(record)); assertEquals(other, store.get(MeetingIntentKind.DOCUMENT_EXPORT, record))
    }
    private class Fixture : MeetingSharingApi {
        var status = 200
        var invalid = false
        var granted = false
        val sent = mutableListOf<Pair<String, SummaryShareRequestDto>>()
        val applied = mutableMapOf<String, SummaryShareReceiptDto>()
        override suspend fun apply(record: String, key: String, body: SummaryShareRequestDto): SummaryShareReceiptDto {
            sent += key to body
            if (status in setOf(400, 403, 409)) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            val existed = applied.containsKey(key)
            val receipt = applied.getOrPut(key) {
                granted = true
                SummaryShareReceiptDto(UUID.randomUUID().toString(), false, SummarySharePreviewDto(record, "Record", null, body.operation, "all_record_summary_versions",
                    body.userIds.map { SummaryShareRecipientDto(it, "Recipient", true, false, false, false, false, false, true, true, null, null) }, false, false, false, false, body.expectedHash))
            }
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return receipt.copy(replayed = existed, appliedPreview = if (invalid) receipt.appliedPreview.copy(grantsMedia = true) else receipt.appliedPreview)
        }
        override suspend fun access(record: String, cursor: String?): SummaryShareAccessPageDto = error("No automatic reads")
        override suspend fun candidates(record: String, scope: String, query: String, cursor: String?): RecordPageDto<SummarySharePersonDto> = error("No automatic reads")
        override suspend fun preview(record: String, body: SummaryShareSelectionDto): SummarySharePreviewDto = error("No automatic reads")
    }
}
