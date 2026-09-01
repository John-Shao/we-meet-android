package com.we.meet.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskFilterBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactFilterSummaryStaysOnOneLineAtPhoneWidth() {
        val summary = "All · Today · Any"

        composeRule.setContent {
            WeMeetTheme(darkTheme = false) {
                Box(Modifier.width(190.dp)) {
                    TaskControlEntry(
                        label = "筛选",
                        summary = summary,
                        onClick = {},
                    )
                }
            }
        }

        val layoutResults = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(summary, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { getTextLayoutResult ->
            getTextLayoutResult(layoutResults)
        }

        assertTrue(layoutResults.single().lineCount == 1)
        assertFalse(layoutResults.single().hasVisualOverflow)
    }
}
