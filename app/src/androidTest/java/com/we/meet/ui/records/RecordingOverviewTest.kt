package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.RecordCapabilitiesDto
import com.we.meet.data.api.dto.RecordDto
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordingOverviewTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun record(index: Int = 1) = RecordDto("record-$index", "audio_recording", "Recording $index",
        "2026-09-16T02:00:00Z", 1, RecordCapabilitiesDto(readSummary = true, readTranscript = true),
        hasSummary = true, retentionMode = "media")

    @Test fun homeOnlyOpensCaptureOnActionAndLimitsHistoryToTen() {
        var captures = 0
        var menus = 0
        var more = 0
        var selected: String? = null
        compose.setContent { WeMeetTheme {
            RecordingHomeContent(Result.success((1..12).map(::record)), true, { menus++ }, { captures++ },
                { selected = it }, { more++ }, {})
        } }
        assertEquals(0, captures)
        compose.onNodeWithContentDescription(label(R.string.meeting_navigation)).performClick()
        assertEquals(1, menus)
        compose.onNodeWithText(label(R.string.records_start_recording)).performClick()
        assertEquals(1, captures)
        compose.onNodeWithText("Recording 1").performClick()
        assertEquals("record-1", selected)
        compose.onNodeWithText("Recording 10").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Recording 11").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.video_more)).performScrollTo().performClick()
        assertEquals(1, more)
    }

    @Test fun emptyHistoryAndLoadFailureKeepCaptureAvailable() {
        var result by mutableStateOf<Result<List<RecordDto>>?>(Result.success(emptyList()))
        var retries = 0
        var captures = 0
        compose.setContent { WeMeetTheme {
            RecordingHomeContent(result, true, {}, { captures++ }, {}, {}, { retries++ })
        } }
        compose.onNodeWithText(label(R.string.recording_history_empty)).assertIsDisplayed()
        compose.runOnIdle { result = Result.failure(IllegalStateException("Offline")) }
        compose.onNodeWithText(label(R.string.records_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.records_start_recording)).performClick()
        assertEquals(1, captures)
        compose.onNodeWithText(label(com.we.meet.design.R.string.common_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun detailLinksOpenTheirOwnRecordAndSummary() {
        var original: String? = null
        var summary: String? = null
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            RecordingDetailContent(record(), { original = it }, { summary = it })
        } } }
        compose.onNodeWithText("Recording 1").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.video_view_record)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.video_view_summary)).performScrollTo().performClick()
        assertEquals("record-1", original)
        assertEquals("record-1", summary)
    }

    @Test fun detailWithoutReadPermissionsHasNoMaterialLinks() {
        compose.setContent { WeMeetTheme { Column {
            RecordingDetailContent(record().copy(capabilities = RecordCapabilitiesDto()),
                { fail("Original is inaccessible") }, { fail("Summary is inaccessible") })
        } } }
        compose.onNodeWithText(label(R.string.video_view_record)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.video_view_summary)).assertDoesNotExist()
    }
}
