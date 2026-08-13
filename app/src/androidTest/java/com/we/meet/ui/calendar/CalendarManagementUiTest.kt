package com.we.meet.ui.calendar

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CalendarManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun colorSheetReturnsSelectedPayload() {
        var selected: String? = null
        val target = CALENDAR_COLOR_PALETTE[3]
        composeRule.setContent {
            WeMeetTheme {
                CalendarColorSheet(
                    selected = CALENDAR_COLOR_PALETTE.first(),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag("calendar-color-$target").performClick()
        composeRule.runOnIdle { assertEquals(target, selected) }
    }
}
