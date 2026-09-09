package com.we.meet.ui.meetingroom

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.MeetingRoomFacilityDto
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingRoomPickerFiltersUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectsAnyLevelAndClearsItWithoutChangingOtherFilters() {
        verifyFilters(darkTheme = false)
    }

    @Test
    fun filtersRemainUsableOnNarrowDarkScreens() {
        verifyFilters(darkTheme = true)
    }

    private fun verifyFilters(darkTheme: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var nodeId by mutableStateOf<String?>(null)
        var capacity by mutableStateOf<Int?>(null)
        var facilities by mutableStateOf(setOf("tv"))
        val nodes = listOf(
            MeetingRoomNodeDto(id = "building", name = "Building A", parent = "campus"),
            MeetingRoomNodeDto(id = "country", name = "Country"),
            MeetingRoomNodeDto(id = "city", name = "City", parent = "country"),
            MeetingRoomNodeDto(id = "campus", name = "Campus", parent = "city"),
        )
        composeRule.setContent {
            WeMeetTheme(darkTheme = darkTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp)) {
                            MeetingRoomPickerFilters(
                                nodes = nodes,
                                facilities = listOf(MeetingRoomFacilityDto(id = "tv", name = "TV")),
                                nodeId = nodeId,
                                onNode = { nodeId = it },
                                capacityMin = capacity,
                                onCapacity = { capacity = it },
                                facilityIds = facilities,
                                onToggleFacility = {
                                    facilities = if (it in facilities) facilities - it else facilities + it
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("mr-filter-level").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Country").assertIsDisplayed()
        composeRule.onNodeWithText("City").assertIsDisplayed()
        composeRule.onNodeWithText("Campus").assertIsDisplayed()
        composeRule.onNodeWithText("Building A").assertIsDisplayed()
        captureForReview("levels", darkTheme)
        composeRule.onNodeWithText("Building A").performClick()
        composeRule.runOnIdle { assertEquals("building", nodeId) }

        composeRule.onNodeWithTag("mr-filter-capacity").performClick()
        composeRule.onNodeWithText(context.getString(R.string.meeting_room_filter_capacity_at_least, 10)).performClick()
        composeRule.onNodeWithTag("mr-filter-level").performClick()
        composeRule.onNodeWithText(context.getString(R.string.meeting_room_filter_level_all)).performClick()
        composeRule.runOnIdle {
            assertEquals(null, nodeId)
            assertEquals(10, capacity)
            assertEquals(setOf("tv"), facilities)
        }
        composeRule.waitUntil(5_000) { composeRule.onNodeWithText("TV").isDisplayed() }
        composeRule.onNodeWithText("TV").performClick()
        composeRule.runOnIdle { assertEquals(emptySet<String>(), facilities) }
        captureForReview("filters", darkTheme)
    }

    /** Opt-in screenshots for local light/dark visual review. */
    private fun captureForReview(name: String, darkTheme: Boolean) {
        if (InstrumentationRegistry.getArguments().getString("meetingRoomScreenshots") != "true") return
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val theme = if (darkTheme) "dark" else "light"
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "meeting-room-$name-$theme.png")
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        target.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
