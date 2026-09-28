package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.UploadTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.UploadTranslationRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class UploadTranslationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val segment = UUID.randomUUID().toString()
    @Volatile private var stale = false
    @Volatile private var complete = true
    @Volatile private var denied = false
    private var longPage = false
    private var singlePage = false
    private var emptyPage = false
    private var speaker = "Speaker"
    private val position = mutableStateOf<Long?>(null)
    private val requests = mutableListOf<UploadTranslationRequestDto>()
    private val exports = mutableListOf<Pair<String, String>>()
    private val item get() = UploadTranslationDto(id, record, "en", "succeeded", 1, stale, 51, 3, 3)
    private val api = object : UploadTranslationApi {
        override suspend fun list(record: String): UploadTranslationListDto {
            if (denied) throw HttpException(Response.error<Any>(404, "".toResponseBody()))
            return UploadTranslationListDto(true, 1, if (complete) listOf(item) else emptyList())
        }
        override suspend fun detail(record: String, translation: String, page: Int) = item.copy(
            results = if (emptyPage) emptyList() else if (longPage) (0..39).map { UploadTranslationSegmentDto(UUID.nameUUIDFromBytes("$it".toByteArray()).toString(), it * 1000L, "Speaker", "Original $it", "Translation $it") } else listOf(UploadTranslationSegmentDto(UUID.nameUUIDFromBytes("$segment-$page".toByteArray()).toString(), 1200, speaker, "Original", if (page == 0) "First translation" else "Last translation", endMs = 3200)),
            nextPage = if (page == 0 && !singlePage) 1 else null,
        )
        override suspend fun generate(record: String, body: UploadTranslationRequestDto): UploadTranslationDto {
            requests += body; throw java.io.IOException("Response lost")
        }
        override suspend fun export(record: String, translation: String, format: String) = "text".toResponseBody()
    }
    private fun label(id: Int) = context.getString(id)
    private fun awaitText(text: String) { compose.waitUntil(9000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    @Before fun resetPreferences() { context.getSharedPreferences("translation_view", android.content.Context.MODE_PRIVATE).edit().clear().commit() }
    private fun show(onSource: ((Long) -> Unit)? = null, fontScale: Float = 1f) {
        val repo = UploadTranslationRepository(api) { "owner" }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            WeMeetTheme { Surface { UploadTranslationPanel("owner", record, repo, { id, fmt -> exports += id to fmt }, onSource, position.value) } }
        } }
    }
    @Test fun compactLayoutTogglesOriginalAndSeeksWithAccessibleTimestamp() {
        singlePage = true
        speaker = "Unknown"
        var sought: Long? = null
        position.value = 1500
        show(onSource = { sought = it }, fontScale = 1.3f)
        awaitText("First translation")
        compose.onNodeWithText(label(R.string.upload_translation_unknown_speaker)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.upload_translation_description)).assertDoesNotExist()
        assertTrue(compose.onNodeWithText("First translation").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Original").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithContentDescription("${label(R.string.upload_translation_play)} 0:01").performClick()
        assertEquals(1200L, sought)
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label(R.string.upload_translation_current_segment))).assertExists()
        val screenshot = File(context.getExternalFilesDir(null), "translation-list-large-font.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText(label(R.string.upload_translation_show_original)).performClick()
        compose.onNodeWithText("Original").assertDoesNotExist()
        compose.onNodeWithText("First translation").assertIsDisplayed()
        assertFalse(context.getSharedPreferences("translation_view", android.content.Context.MODE_PRIVATE).getBoolean("owner:show_original", true))
        compose.runOnIdle { position.value = 3200 }
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, label(R.string.upload_translation_current_segment))).assertDoesNotExist()
    }

    @Test fun restoresOriginalVisibilityAndOpensExplanationFromMoreMenu() {
        singlePage = true
        context.getSharedPreferences("translation_view", android.content.Context.MODE_PRIVATE).edit().putBoolean("owner:show_original", false).commit()
        show()
        awaitText("First translation")
        compose.onNodeWithText("Original").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.upload_translation_show_original)).performClick()
        compose.onNodeWithText("Original").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).performClick()
        compose.onNodeWithText(label(R.string.upload_translation_info)).performClick()
        compose.onNodeWithText(label(R.string.upload_translation_description)).assertIsDisplayed()
    }
    @Test fun toolbarRemainsReachableWhileReadingLongTranslation() {
        longPage = true
        show()
        awaitText("Translation 0")
        val tools = compose.onNodeWithContentDescription(label(R.string.records_translation_language))
        val top = tools.fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        tools.assertIsDisplayed()
        assertEquals(top, tools.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.records_export_translation, "TXT")).assertIsDisplayed()
    }

    @Test fun emptyLanguageHidesOriginalControlWithoutResettingPreference() {
        singlePage = true
        show()
        awaitText("First translation")
        for (visible in listOf(false, true)) {
            compose.onNodeWithText(label(R.string.upload_translation_show_original)).performClick()
            compose.onNodeWithContentDescription(label(R.string.records_translation_language)).performClick()
            compose.onNodeWithText(label(R.string.archives_zh)).performClick()
            awaitText(label(R.string.upload_translation_empty))
            compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertDoesNotExist()
            assertEquals(visible, context.getSharedPreferences("translation_view", android.content.Context.MODE_PRIVATE).getBoolean("owner:show_original", true))
            compose.onNodeWithContentDescription(label(R.string.records_translation_language)).performClick()
            compose.onNodeWithText(label(R.string.archives_en)).performClick()
            awaitText("First translation")
            if (visible) {
                compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertIsSelected()
                compose.onNodeWithText("Original").assertIsDisplayed()
            } else {
                compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertIsNotSelected()
                compose.onNodeWithText("Original").assertDoesNotExist()
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun emptyTranslationRowsHideOriginalControl() {
        emptyPage = true; singlePage = true
        show()
        awaitText(label(R.string.archives_en))
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).performClick()
        awaitText(context.getString(R.string.records_export_translation, "TXT"))
        compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertDoesNotExist()
    }

    @Test fun alignedTranslationPagesAndExportIdentity() {
        show(); awaitText("First translation")
        compose.onNodeWithText(label(R.string.upload_translation_original).replace("%1\$s", "Original"))
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.records_export_translation, "TXT")).performClick()
        assertEquals(listOf(id to "txt"), exports)
        awaitText("Last translation")
        compose.onNodeWithText("First translation").assertExists()
    }
    @Test fun staleTranslationExplainsWhyExportIsUnavailable() {
        stale = true; show(); awaitText("First translation")
        compose.onNodeWithText(label(R.string.upload_translation_stale)).assertExists()
        compose.onNodeWithText("TXT").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.upload_translation_regenerate)).assertExists()
        compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertIsDisplayed()
    }
    @Test fun responseLossReusesTheSameIntent() {
        complete = false; show(); awaitText(label(R.string.upload_translation_generate))
        compose.onNodeWithText(label(R.string.upload_translation_show_original)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.upload_translation_generate)).performClick()
        awaitText(label(R.string.upload_translation_uncertain))
        compose.onNodeWithText(label(R.string.upload_translation_check)).performClick()
        compose.waitUntil(9000) { requests.size == 2 }
        assertEquals(requests[0], requests[1])
    }
    @Test fun revocationClearsTheTranslationOnRevalidation() {
        show(); awaitText("First translation")
        denied = true
        compose.waitUntil(12000) { compose.onAllNodesWithText("First translation").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("TXT").assertDoesNotExist()
    }
}
