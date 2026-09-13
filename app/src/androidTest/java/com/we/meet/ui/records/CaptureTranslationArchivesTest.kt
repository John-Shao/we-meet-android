package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.CaptureTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class CaptureTranslationArchivesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = UUID.randomUUID().toString()
    private var viewer by mutableStateOf(UUID.randomUUID().toString())
    private val capture = UUID.randomUUID().toString()
    private val api = Fixture()
    private fun label(id: Int) = context.getString(id)
    private fun awaitText(text: String) { compose.waitUntil(9000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(id: Int) {
        awaitText(label(id))
        val node = compose.onNodeWithText(label(id))
        if (id !in setOf(R.string.archives_back, R.string.records_refresh)) node.performScrollTo()
        node.performClick()
    }
    private fun show(dark: Boolean = false) {
        val repo = CaptureTranslationRepository(api) { viewer }
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { CaptureTranslationArchives(viewer, capture, record, repo) } } }
    }
    private fun open() = click(R.string.capture_translation_speech)
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "capture-translation-archive-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun listsSavedArchiveAndPaginatesSeparateConfirmedTranslations() {
        show(); awaitText(label(R.string.capture_translation_speech)); screenshot("list-light")
        open(); awaitText("First confirmed translation")
        compose.onNodeWithText(label(R.string.archives_timing)).assertExists()
        click(R.string.records_next); awaitText("Second confirmed translation")
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
        assertEquals("segment-next", api.segmentCursor)
        click(R.string.records_previous); awaitText("First confirmed translation")
        click(R.string.archives_back); awaitText(label(R.string.capture_translation_speech))
        click(R.string.records_next); awaitText(label(R.string.archives_empty))
        assertEquals("archive-next", api.archiveCursor)
        click(R.string.records_previous); awaitText(label(R.string.capture_translation_speech))
    }
    @Test fun personalIncompleteArchiveShowsScopeAndReverseTargetWithoutSourceSeek() {
        api.privateArchive = true; api.status = "incomplete"
        show(dark = true); open(); awaitText("First confirmed translation")
        compose.onNodeWithText(label(R.string.archives_private_scope)).assertExists()
        compose.onNodeWithText(label(R.string.archives_incomplete_hint)).assertExists()
        compose.onNodeWithText(label(R.string.archives_en) + " → " + label(R.string.archives_zh)).assertExists()
        screenshot("personal-dark")
        compose.onNodeWithText(label(R.string.records_source)).assertDoesNotExist()
    }
    @Test fun permissionRevocationRemovesTextOnExplicitRefresh() {
        show(); open(); awaitText("First confirmed translation")
        api.httpStatus = 403; click(R.string.records_refresh)
        awaitText(label(R.string.archives_unavailable))
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
    }
    @Test fun backgroundRechecksAccessAndNeverRestoresOldTranslation() {
        show(); open(); awaitText("First confirmed translation")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.httpStatus = 404
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitText(label(R.string.archives_unavailable))
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
    }
    @Test fun accountChangeClearsSelectionAndPriorText() {
        show(); open(); awaitText("First confirmed translation")
        api.httpStatus = 403
        compose.runOnIdle { viewer = UUID.randomUUID().toString() }
        awaitText(label(R.string.archives_unavailable))
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.archives_back)).assertDoesNotExist()
    }
    private inner class Fixture : CaptureTranslationApi {
        val archiveId = UUID.randomUUID().toString()
        val run = UUID.randomUUID().toString()
        @Volatile var httpStatus = 200
        @Volatile var status = "complete"
        @Volatile var privateArchive = false
        @Volatile var archiveCursor: String? = null
        @Volatile var segmentCursor: String? = null
        private fun fail() { if (httpStatus != 200) throw HttpException(Response.error<Any>(httpStatus, "{}".toResponseBody())) }
        private fun row() = CaptureTranslationArchiveDto(archiveId, run, capture, 1, CaptureTranslationConfigDto("zh", "en", "push_to_talk", false, true, CaptureTranslationRepository.MODEL, "cn-beijing"), status, 2, "2026-09-13T00:00:00Z")
        override suspend fun archives(capture: String, cursor: String?): CaptureTranslationArchivesDto {
            assertEquals(this@CaptureTranslationArchivesTest.capture, capture); archiveCursor = cursor; fail()
            return CaptureTranslationArchivesDto(capture, record, if (cursor == null) listOf(row()) else emptyList(), if (cursor == null) "archive-next" else null)
        }
        override suspend fun segments(capture: String, archive: String, cursor: String?): CaptureTranslatedSegmentsDto {
            assertEquals(this@CaptureTranslationArchivesTest.capture, capture); assertEquals(archiveId, archive); segmentCursor = cursor; fail()
            val text = if (cursor == null) "First confirmed translation" else "Second confirmed translation"
            val segment = CaptureTranslatedSegmentDto(UUID.randomUUID().toString(), if (cursor == null) 1 else 2, capture, if (privateArchive) "reverse" else "forward", if (privateArchive) "zh" else "en", text, "2026-09-13T00:00:01Z", "delivery", null)
            return CaptureTranslatedSegmentsDto(capture, record, archiveId, status, run, 1, listOf(segment), if (cursor == null) "segment-next" else null)
        }
        override suspend fun state(capture: String): CaptureTranslationStateDto = error("unused")
        override suspend fun control(capture: String, lease: String, body: okhttp3.RequestBody): CaptureTranslationReceiptDto = error("unused")
        override suspend fun ticket(capture: String, lease: String, body: CaptureTranslationTicketRequestDto): CaptureTranslationTicketDto = error("unused")
    }
}
