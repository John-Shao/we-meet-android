package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingSummaryApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingSummaryRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.util.UUID
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordOverviewLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun overviewAndChaptersShareOnePageAndReferencesExpand() {
        val viewer = "overview-display-${UUID.randomUUID()}"
        val record = RecordDto(UUID.randomUUID().toString(), "upload", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(readTranscript = true))
        val fixture = Fixture()
        val refs = listOf(5000L, 1000L, 3000L).map { RecordReferenceDto(UUID.randomUUID().toString(), 1, it) }
        fixture.state = fixture.state.copy(version = fixture.state.version!!.copy(content =
            RecordOverviewContentDto("Existing overview", listOf(RecordOverviewTopicDto("Chapter title", "Chapter details", refs)))))
        val selected = mutableListOf<Pair<String, RecordReferenceDto>>()
        var openedMinutes = false
        val repository = MeetingSummaryRepository(fixture) { viewer }
        compose.setContent { WeMeetTheme {
            RecordOverview(viewer, record, repository, { viewer }, onOpenMinutes = { openedMinutes = true }, onSource = { snapshot, ref -> selected.add(snapshot to ref) })
        } }
        compose.waitUntil(8000) { compose.onAllNodesWithText("Existing overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Chapter title").assertIsDisplayed()
        compose.onNodeWithText("Existing overview").assertIsDisplayed()
        compose.onNodeWithText("Chapter details").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.record_overview_language)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.record_overview_open_minutes)).performClick()
        compose.runOnIdle { assertEquals(true, openedMinutes) }
        compose.onAllNodesWithText(label(R.string.record_overview_regenerate)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.record_overview_synopsis_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.records_chapters)).assertIsDisplayed()
        File(context.getExternalFilesDir(null), "combined-overview.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        fun source(ms: Long) = context.getString(R.string.records_source_at, sourceTime(ms))
        compose.onNodeWithText(source(1000)).performClick()
        compose.onNodeWithText(source(3000)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.record_overview_more_sources, 2)).performClick()
        compose.onNodeWithText(source(3000)).performScrollTo().performClick()
        compose.onNodeWithText(source(5000)).assertExists()
        compose.onNodeWithText(label(R.string.record_overview_hide_sources)).performScrollTo().performClick()
        compose.onNodeWithText(source(3000)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(1000L, 3000L), selected.map { it.second.startMs })
            assertEquals(listOf(fixture.state.version!!.inputSnapshotId), selected.map { it.first }.distinct())
            assertEquals(0, fixture.generations)
        }
    }

    @Test fun changingLanguagePreservesContentAndDoesNotGenerate() {
        val viewer = "overview-language-${UUID.randomUUID()}"
        val record = RecordDto(UUID.randomUUID().toString(), "upload", "Fixture", "2026-09-13T00:00:00Z", 1, RecordCapabilitiesDto(readTranscript = true))
        val fixture = Fixture()
        val repository = MeetingSummaryRepository(fixture) { viewer }
        compose.setContent { WeMeetTheme {
            RecordOverview(viewer, record, repository, { viewer }, onSource = { _, _ -> })
        } }
        compose.waitUntil(8000) { compose.onAllNodesWithText("Existing overview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { !compose.onNodeWithText(label(R.string.record_overview_language)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithText(label(R.string.record_overview_language)).performClick()
        compose.onNodeWithText(label(R.string.record_overview_follow_source)).assertIsSelected()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.record_overview_language_save)).performClick()
        compose.waitUntil(8000) { fixture.state.outputLanguage == "en" && compose.onAllNodesWithText(label(R.string.record_overview_language_save)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Existing overview").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.record_overview_language)).performClick()
        compose.onNodeWithText("English").assertIsSelected()
        assertEquals(1, fixture.languageWrites)
        assertEquals(0, fixture.generations)
    }

    private class Fixture : MeetingSummaryApi {
        var languageWrites = 0
        var generations = 0
        var state = RecordOverviewStateDto(1, true, true, true, null,
            RecordOverviewVersionDto(UUID.randomUUID().toString(), RecordOverviewContentDto("Existing overview", emptyList()),
                "2026-09-13T00:00:00Z", UUID.randomUUID().toString(), 1, true, "finished"))
        override suspend fun overview(record: String) = state
        override suspend fun setOverviewLanguage(record: String, body: OverviewLanguageRequestDto): OverviewLanguageDto {
            assertEquals(state.outputLanguage, body.expectedOutputLanguage)
            languageWrites++
            state = state.copy(outputLanguage = body.outputLanguage)
            return OverviewLanguageDto(body.outputLanguage)
        }
        override suspend fun requestOverview(record: String, key: String, body: RequestBody): SummaryAcceptedDto { generations++; error("Unexpected generation") }
        override suspend fun progress(record: String): SummaryProgressDto = error("Unexpected minutes")
        override suspend fun request(record: String, key: String, body: RequestBody): SummaryAcceptedDto = error("Unexpected minutes")
        override suspend fun automation(record: String): SummaryAutomationDto = error("Unexpected automation")
        override suspend fun control(record: String, key: String, body: RequestBody): SummaryAutomationAcceptedDto = error("Unexpected automation")
    }
}
