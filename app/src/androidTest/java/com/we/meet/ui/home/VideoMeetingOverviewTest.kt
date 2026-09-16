package com.we.meet.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.RecordSource
import com.we.meet.ui.history.MeetingRecordLinks
import com.we.meet.ui.records.RecordLibraryScreen
import com.we.meet.ui.theme.WeMeetTheme
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoMeetingOverviewTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val roomId = "11111111-1111-4111-8111-111111111111"
    private val sessionId = "22222222-2222-4222-8222-222222222222"
    private val record = RecordDto("33333333-3333-4333-8333-333333333333", "meeting", "Meeting fixture", "2026-09-15T00:00:00Z", 1, RecordCapabilitiesDto(readSummary = true, readTranscript = true), meetingSessionId = sessionId, sourceSessionId = sessionId, hasSummary = true)
    private fun label(id: Int) = context.getString(id)

    @Test fun historyHasTwentyRowsAndMoreSelectsVideoRecords() {
        var more = false
        var selected: RoomDto? = null
        val rows = (0..23).map { RoomDto(roomId, "Session $it", "12345678", null, null, livekit = null, meeting_session_id = "session-$it", started_at = "2026-09-15T00:00:00Z", status = if (it == 0) "active" else "ended") }
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ScheduledMeetingsList(emptyList(), {})
            VideoHistoryList(rows, { selected = it }, { more = true })
        } } }
        compose.onNodeWithText(label(R.string.scheduled_section_title)).assertExists()
        compose.onNodeWithText("Session 2").performScrollTo().performClick()
        assertEquals("session-2", selected?.meeting_session_id)
        compose.onNodeWithText("Session 19").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Session 20").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.video_more)).performScrollTo().performClick()
        assertTrue(more)
    }

    @Test fun moreInitialFilterIsUsedByTheFirstLibraryRequest() {
        val sources = mutableListOf<Any?>()
        val api = Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java)) { _, method, args ->
            check(method.name == "records") { "Unexpected body request: ${method.name}" }
            sources += args[1]
            RecordPageDto(listOf(record))
        } as MeetingRecordApi
        compose.setContent { WeMeetTheme {
            RecordLibraryScreen(MeetingRecordRepository(api) { "viewer" }, "viewer", false, {}, {}, initialSource = RecordSource.MEETING)
        } }
        compose.waitUntil(5_000) { sources.isNotEmpty() }
        assertTrue(sources.all { it == "meeting" })
    }

    @Test fun detailsResolveExactSessionWithoutLoadingContent() {
        var openedRecord: String? = null
        var openedSummary: String? = null
        val calls = mutableListOf<String>()
        val api = Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java)) { _, method, args ->
            calls += method.name
            check(method.name == "resolve") { "History detail must not load content" }
            assertEquals(roomId, args[0]); assertEquals(sessionId, args[1])
            record
        } as MeetingRecordApi
        compose.setContent { WeMeetTheme {
            MeetingRecordLinks(MeetingRecordRepository(api) { "viewer" }, "viewer", roomId, sessionId, { openedRecord = it }, { openedSummary = it })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label(R.string.video_view_record)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.video_view_record)).performClick()
        compose.onNodeWithText(label(R.string.video_view_summary)).performClick()
        assertEquals(record.id, openedRecord); assertEquals(record.id, openedSummary)
        assertEquals(listOf("resolve"), calls)
    }
}
