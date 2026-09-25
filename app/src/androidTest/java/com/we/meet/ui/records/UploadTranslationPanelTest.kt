package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
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
    private val requests = mutableListOf<UploadTranslationRequestDto>()
    private val exports = mutableListOf<Pair<String, String>>()
    private val item get() = UploadTranslationDto(id, record, "en", "succeeded", 1, stale, 51, 3, 3)
    private val api = object : UploadTranslationApi {
        override suspend fun list(record: String): UploadTranslationListDto {
            if (denied) throw HttpException(Response.error<Any>(404, "".toResponseBody()))
            return UploadTranslationListDto(true, 1, if (complete) listOf(item) else emptyList())
        }
        override suspend fun detail(record: String, translation: String, page: Int) = item.copy(
            results = if (longPage) (0..39).map { UploadTranslationSegmentDto(UUID.nameUUIDFromBytes("$it".toByteArray()).toString(), it * 1000L, "Speaker", "Original $it", "Translation $it") } else listOf(UploadTranslationSegmentDto(segment, 1200, "Speaker", "Original", if (page == 0) "First translation" else "Last translation")),
            nextPage = if (page == 0) 1 else null,
        )
        override suspend fun generate(record: String, body: UploadTranslationRequestDto): UploadTranslationDto {
            requests += body; throw java.io.IOException("Response lost")
        }
        override suspend fun export(record: String, translation: String, format: String) = "text".toResponseBody()
    }
    private fun label(id: Int) = context.getString(id)
    private fun awaitText(text: String) { compose.waitUntil(9000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun show() {
        val repo = UploadTranslationRepository(api) { "owner" }
        compose.setContent { WeMeetTheme { Surface { UploadTranslationPanel("owner", record, repo, { id, fmt -> exports += id to fmt }) } } }
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

    @Test fun alignedTranslationPagesAndExportIdentity() {
        show(); awaitText("First translation")
        compose.onNodeWithText(label(R.string.upload_translation_original).replace("%1\$s", "Original"))
        compose.onNodeWithContentDescription(label(R.string.records_panel_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.records_export_translation, "TXT")).performClick()
        assertEquals(listOf(id to "txt"), exports)
        compose.onNodeWithText(label(R.string.records_next)).performScrollTo().performClick()
        awaitText("Last translation")
        compose.onNodeWithText("First translation").assertDoesNotExist()
    }
    @Test fun staleTranslationExplainsWhyExportIsUnavailable() {
        stale = true; show(); awaitText("First translation")
        compose.onNodeWithText(label(R.string.upload_translation_stale)).assertExists()
        compose.onNodeWithText("TXT").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.upload_translation_regenerate)).assertExists()
    }
    @Test fun responseLossReusesTheSameIntent() {
        complete = false; show(); awaitText(label(R.string.upload_translation_generate))
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
