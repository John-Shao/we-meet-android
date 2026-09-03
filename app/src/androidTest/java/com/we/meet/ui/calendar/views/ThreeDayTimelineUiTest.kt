package com.we.meet.ui.calendar.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.ui.theme.WeMeetTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeDayTimelineUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun threeColumnsFillViewportWithoutHorizontalScrollSemantics() {
        val anchor = LocalDate.of(2026, 8, 14)
        val days = threeDayColumnDays(anchor)
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    ThreeDayTimelineView(
                        anchorDate = anchor,
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onDayClick = {},
                        onSlotTap = { _, _ -> },
                        visibleStartMin = 8 * 60,
                        visibleEndMin = 12 * 60,
                    )
                }
            }
        }

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)

        val viewBounds = composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val headerBounds = days.map { date ->
            composeRule.onNodeWithTag(threeDayHeaderTestTag(date))
                .fetchSemanticsNode().boundsInRoot
        }
        val expectedWidth = headerBounds.first().width
        headerBounds.forEach { bounds ->
            assertEquals(expectedWidth, bounds.width, 1f)
        }
        assertEquals(viewBounds.right, headerBounds.last().right, 1f)
    }

    @Test
    fun tappingSecondColumnAfterPagingSelectsSecondRenderedDate() {
        val anchor = LocalDate.of(2026, 9, 10)
        var currentAnchor by mutableStateOf(anchor.minusDays(3))
        var tappedDate: LocalDate? = null
        var draft by mutableStateOf<DraftSlot?>(null)
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    ThreeDayTimelineView(
                        anchorDate = currentAnchor,
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onDayClick = {},
                        onSlotTap = { date, minute ->
                            tappedDate = date
                            draft = draftSlotAt(date, minute, 60, 14 * 60, 20 * 60)
                        },
                        onDateSwipe = { currentAnchor = it },
                        visibleStartMin = 14 * 60,
                        visibleEndMin = 20 * 60,
                        draft = draft,
                        draftLabel = "Add event",
                    )
                }
            }
        }

        val view = composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG)
        view.performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(anchor, currentAnchor) }

        val viewBounds = view.fetchSemanticsNode().boundsInRoot
        val secondHeaderBounds = composeRule
            .onNodeWithTag(threeDayHeaderTestTag(anchor.plusDays(1)))
            .fetchSemanticsNode().boundsInRoot
        view.performTouchInput {
            click(
                Offset(
                    x = secondHeaderBounds.center.x - viewBounds.left,
                    y = viewBounds.height * 0.55f,
                ),
            )
        }

        composeRule.runOnIdle { assertEquals(anchor.plusDays(1), tappedDate) }
    }

    @Test
    fun horizontalDragMovesToAdjacentThreeDayWindow() {
        val anchor = LocalDate.of(2026, 8, 14)
        var currentAnchor by mutableStateOf(anchor)
        var changedDate: LocalDate? = null
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    ThreeDayTimelineView(
                        anchorDate = currentAnchor,
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onDayClick = {},
                        onSlotTap = { _, _ -> },
                        onDateSwipe = {
                            changedDate = it
                            currentAnchor = it
                        },
                        visibleStartMin = 8 * 60,
                        visibleEndMin = 12 * 60,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG).performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(anchor.plusDays(3), changedDate) }

        composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG).performTouchInput { swipeRight() }
        composeRule.runOnIdle { assertEquals(anchor, changedDate) }
    }

    @Test
    fun threeDayPagingKeepsVerticalScrollPosition() {
        val anchor = LocalDate.of(2026, 8, 14)
        var currentAnchor by mutableStateOf(anchor)
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    ThreeDayTimelineView(
                        anchorDate = currentAnchor,
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onDayClick = {},
                        onSlotTap = { _, _ -> },
                        onDateSwipe = { currentAnchor = it },
                    )
                }
            }
        }

        scrollTimelineTowardEnd()
        val beforePaging = verticalScrollPosition()

        composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG)
            .performTouchInput { swipeRight() }

        composeRule.runOnIdle { assertEquals(anchor.minusDays(3), currentAnchor) }
        assertEquals(beforePaging, verticalScrollPosition(), 1f)

        composeRule.onNodeWithTag(THREE_DAY_VIEW_TEST_TAG)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle { assertEquals(anchor, currentAnchor) }
        assertEquals(beforePaging, verticalScrollPosition(), 1f)
    }

    @Test
    fun dayPagingKeepsVerticalScrollPosition() {
        val date = LocalDate.of(2026, 8, 14)
        var currentDate by mutableStateOf(date)
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    DayTimelineView(
                        date = currentDate,
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onSlotTap = { _, _ -> },
                        onDateSwipe = { currentDate = it },
                    )
                }
            }
        }

        scrollTimelineTowardEnd()
        val beforePaging = verticalScrollPosition()

        composeRule.onNodeWithTag(DAY_VIEW_TEST_TAG)
            .performTouchInput { swipeLeft() }

        composeRule.runOnIdle { assertEquals(date.plusDays(1), currentDate) }
        assertEquals(beforePaging, verticalScrollPosition(), 1f)

        composeRule.onNodeWithTag(DAY_VIEW_TEST_TAG)
            .performTouchInput { swipeRight() }

        composeRule.runOnIdle { assertEquals(date, currentDate) }
        assertEquals(beforePaging, verticalScrollPosition(), 1f)
    }

    @Test
    fun dayViewUsesFixedHorizontalViewport() {
        composeRule.setContent {
            WeMeetTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    DayTimelineView(
                        date = LocalDate.of(2026, 8, 14),
                        eventsByDay = emptyMap(),
                        onEventClick = {},
                        onSlotTap = { _, _ -> },
                        visibleStartMin = 8 * 60,
                        visibleEndMin = 12 * 60,
                    )
                }
            }
        }

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun scrollTimelineTowardEnd() {
        repeat(4) {
            composeRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
                useUnmergedTree = true,
            ).onFirst().performTouchInput { swipeUp() }
        }
        composeRule.waitForIdle()
    }

    private fun verticalScrollPosition(): Float = composeRule.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    ).onFirst().fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
}
