package com.we.meet.ui.records

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordMediaTimingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = RecordDto("record", "audio_recording", "title", "2026-09-20T00:00:00Z", 1)
    @Test fun partialAudioNeverClaimsCompleteDuration() {
        compose.setContent { WeMeetTheme { RecordInfo(record.copy(mediaTiming = RecordMediaTimingDto(null, 5000, "partial_audio")), null, "viewer") } }
        compose.onNodeWithText(context.getString(R.string.records_media_duration_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.records_media_partial)).assertIsDisplayed()
        compose.onNodeWithText("0:05").assertIsDisplayed()
    }
    @Test fun completeMetadataShowsFullDuration() {
        compose.setContent { WeMeetTheme { RecordInfo(record.copy(mediaTiming = RecordMediaTimingDto(60000, 60000, "saved_audio")), null, "viewer") } }
        compose.onNodeWithText("1:00").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.records_media_partial)).assertDoesNotExist()
    }
}
