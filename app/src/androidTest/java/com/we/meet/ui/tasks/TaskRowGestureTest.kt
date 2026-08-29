package com.we.meet.ui.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRowGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionOnlyOpensFromLongPress() {
        val task = TaskItem(
            id = "gesture-task",
            title = "Gesture task",
            assignee = "User",
            dueLabel = "",
            listName = "",
            section = "",
        )
        var actionCount = 0

        composeRule.setContent {
            WeMeetTheme(darkTheme = false) {
                TaskRow(
                    task = task,
                    onClick = {},
                    onToggleDone = {},
                    onLongClick = { actionCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(taskRowTestTag(task.id)).performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(0, actionCount) }

        composeRule.onNodeWithTag(taskRowTestTag(task.id)).performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(1, actionCount) }
    }
}
