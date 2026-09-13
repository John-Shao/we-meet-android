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
import com.we.meet.data.api.TranslationArchiveApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.TranslationArchiveRepository
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
class RecordTranslationArchivesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = UUID.randomUUID().toString()
    private var viewer by mutableStateOf("owner")
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
        val repo = TranslationArchiveRepository(api) { viewer }
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { RecordTranslationArchives(viewer, record, repo) } } }
    }
    private fun open() = click(if (api.privateArchive) R.string.archives_private else R.string.archives_shared)
    private fun screenshot(name: String) {
        File(context.getExternalFilesDir(null), "translation-archive-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun listsSavedArchiveAndPaginatesSeparateConfirmedTranslations() {
        show(); awaitText(label(R.string.archives_shared)); screenshot("list-light")
        open(); awaitText("First confirmed translation")
        compose.onNodeWithText(label(R.string.archives_timing)).assertExists()
        click(R.string.records_next); awaitText("Second confirmed translation")
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
        assertEquals("segment-next", api.segmentCursor)
        click(R.string.records_previous); awaitText("First confirmed translation")
        click(R.string.archives_back); awaitText(label(R.string.archives_shared))
        click(R.string.records_next); awaitText(label(R.string.archives_empty))
        assertEquals("archive-next", api.archiveCursor)
        click(R.string.records_previous); awaitText(label(R.string.archives_shared))
    }
    @Test fun personalIncompleteArchiveShowsScopeAndReverseTargetWithoutSourceSeek() {
        api.privateArchive = true; api.status = "incomplete"
        show(dark = true); open(); awaitText("First confirmed translation")
        compose.onNodeWithText(label(R.string.archives_private_scope)).assertExists()
        compose.onNodeWithText(label(R.string.archives_incomplete_hint)).assertExists()
        compose.onNodeWithText("Speaker · " + label(R.string.archives_zh)).assertExists()
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
        compose.runOnIdle { viewer = "different" }
        awaitText(label(R.string.archives_unavailable))
        compose.onNodeWithText("First confirmed translation").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.archives_back)).assertDoesNotExist()
    }
    private inner class Fixture : TranslationArchiveApi {
        val archiveId = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        @Volatile var httpStatus = 200
        @Volatile var status = "complete"
        @Volatile var privateArchive = false
        @Volatile var archiveCursor: String? = null
        @Volatile var segmentCursor: String? = null
        private fun fail() { if (httpStatus != 200) throw HttpException(Response.error<Any>(httpStatus, "{}".toResponseBody())) }
        private fun row() = TranslationArchiveDto(archiveId, if (privateArchive) "private" else "channel", if (privateArchive) "push_to_talk" else "simultaneous", if (privateArchive) "zh" else null, "en", 1, status, 2, "2026-09-13T00:00:00Z")
        override suspend fun archives(record: String, cursor: String?): TranslationArchivePageDto {
            assertEquals(this@RecordTranslationArchivesTest.record, record); archiveCursor = cursor; fail()
            return TranslationArchivePageDto(if (cursor == null) listOf(row()) else emptyList(), if (cursor == null) "archive-next" else null)
        }
        override suspend fun segments(record: String, archive: String, cursor: String?): TranslationSegmentPageDto {
            assertEquals(this@RecordTranslationArchivesTest.record, record); assertEquals(archiveId, archive); segmentCursor = cursor; fail()
            val selected = row()
            val text = if (cursor == null) "First confirmed translation" else "Second confirmed translation"
            val segment = TranslationSegmentDto(source, if (cursor == null) 1 else 2, source, "PA_speaker", "Speaker", if (privateArchive) "reverse" else "forward", if (privateArchive) "zh" else "en", text, "2026-09-13T00:00:01Z", "delivery", null)
            return TranslationSegmentPageDto(listOf(segment), if (cursor == null) "segment-next" else null, archiveId, status, selected.target, selected.sourceKind, selected.mode, selected.source)
        }
    }
}
