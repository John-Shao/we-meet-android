package com.we.meet.ui.meetingroom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.data.api.dto.MeetingRoomTimelineEntryDto
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingRoomScheduleUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun horizontalDragMovesToTheNextDayAndKeepsVerticalPosition() {
        val initialDate = LocalDate.of(2026, 8, 15)
        var selectedDate by mutableStateOf(initialDate)
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TestMeetingRoomSchedule(
                        date = selectedDate,
                        onSelectDate = { selectedDate = it },
                    )
                }
            }
        }

        repeat(3) {
            composeRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
                useUnmergedTree = true,
            ).onFirst().performTouchInput { swipeUp() }
        }
        composeRule.waitForIdle()
        val beforePaging = verticalScrollPosition()

        composeRule.onNodeWithTag(MEETING_ROOM_SCHEDULE_TEST_TAG)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle { assertEquals(initialDate.plusDays(1), selectedDate) }
        assertEquals(beforePaging, verticalScrollPosition(), 1f)
    }

    @Test
    fun timeRailUsesAFixedHorizontalViewport() {
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    TestMeetingRoomSchedule(
                        date = LocalDate.of(2026, 8, 15),
                        onSelectDate = {},
                    )
                }
            }
        }

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun verticalScrollPosition(): Float = composeRule.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    ).onFirst().fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
}

@androidx.compose.runtime.Composable
private fun TestMeetingRoomSchedule(
    date: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    MeetingRoomSchedule(
        room = MeetingRoomTimelineEntryDto(id = "room-1", name = "Focus room"),
        date = date,
        loading = false,
        blocksByDate = emptyMap(),
        rangeStart = 0,
        rangeEnd = 24 * 60,
        workingStart = 9 * 60,
        workingEnd = 18 * 60,
        zone = ZoneId.of("UTC"),
        draft = null,
        draftConflict = false,
        conflictMessage = "",
        onBack = {},
        onSelectDate = onSelectDate,
        onOpenRoomInfo = {},
        onDraftAdjust = {},
        onSlotTap = { _, _ -> },
        onDraftConfirm = {},
        onBlockTap = {},
        selectedBlockKey = null,
        onBlockSelect = {},
        onBlockMove = { _, _, _, _ -> },
        onRailTap = {},
    )
}
