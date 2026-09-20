package com.we.meet.ui.records

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeakerTimelineTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val timeline = RecordSpeakerTimelineDto("recognized_extent", "partial", extentMs = 7000,
        intervals = listOf(RecordSpeechIntervalDto(0, 1000), RecordSpeechIntervalDto(4000, 6000)))
    @Test fun fullMediaRulerPreservesTrailingSilence() {
        compose.setContent { WeMeetTheme { SpeakerTimeline(timeline, mediaDuration = 10000) } }
        compose.onNodeWithText(context.getString(R.string.records_media_ruler, "0:10")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_intervals, 2)).performClick()
        compose.onNodeWithText("0:04 – 0:06").assertIsDisplayed()
    }
    @Test fun shorterMediaMetadataDoesNotClipRecognizedSpeech() {
        compose.setContent { WeMeetTheme { SpeakerTimeline(timeline, mediaDuration = 5000) } }
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_basis, "0:07")).assertIsDisplayed()
    }
    @Test fun seeksExactSpeechStartUsingAccessibleTarget() {
        var sought: Long? = null
        compose.setContent { WeMeetTheme { SpeakerTimeline(timeline) { sought = it } } }
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_intervals, 2)).performClick()
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_seek, "0:04", "0:06")).performClick()
        compose.runOnIdle { assertEquals(4000L, sought) }
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_partial)).assertIsDisplayed()
    }
    @Test fun readOnlyTimelineNeverOffersPlayback() {
        compose.setContent { WeMeetTheme { SpeakerTimeline(timeline) } }
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_intervals, 2)).performClick()
        compose.onNodeWithText("0:04 – 0:06").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_seek, "0:04", "0:06")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_read_only)).assertIsDisplayed()
    }
    @Test fun unavailableTimelineDoesNotExposeControls() {
        compose.setContent { WeMeetTheme { SpeakerTimeline(timeline.copy(status = "unavailable")) {} } }
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.speaker_timeline_intervals, 2)).assertDoesNotExist()
    }
}
