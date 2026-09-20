package com.we.meet.ui.records

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.ReplacementConfirmation
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TranscriptReplacementTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val record = UUID.randomUUID().toString()
    private val batch = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    private val writes = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var status = 200
    @Volatile private var showHistory = false
    @Volatile private var undone = false
    private var changed = 0
    private fun label(id: Int) = context.getString(id)
    private val receipt get() = """{"id":"$batch","find":"word","replacement":"world","changed_segments":1,"created_at":"2026-09-20T07:00:00Z","undone":$undone}"""
    private fun repository(durable: Boolean = false, viewer: () -> String? = { "batch26-owner" }): MeetingRecordRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            if (request.method == "POST") writes += path to body
            val code = if (request.method == "GET" || path.endsWith("preview/")) 200 else status
            val response = when {
                request.method == "GET" -> """{"results":${if (showHistory) "[$receipt]" else "[]"}}"""
                path.endsWith("preview/") -> """{"record_id":"$record","preview_hash":"$hash","occurrences":1,"changes":[{"id":"$batch","before":"Hello word","after":"Hello world","start_ms":1000}]}"""
                path.endsWith("undo/") -> { undone = true; receipt }
                else -> receipt
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(MeetingRecordApi::class.java)
        return if (durable) MeetingRecordRepository(api, context, viewer) else MeetingRecordRepository(api, viewer)
    }
    private fun show() {
        val repo = repository()
        compose.setContent { WeMeetTheme { TranscriptReplacementControl(repo, "batch26-owner", record) { changed++ } } }
        compose.onNodeWithText(label(R.string.batch_correction_title)).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(label(R.string.batch_correction_find)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    private fun prepare() {
        show()
        compose.onNodeWithText(label(R.string.batch_correction_find)).performTextInput("word")
        compose.onNodeWithText(label(R.string.batch_correction_replacement)).performTextInput("world")
        compose.onNodeWithText(label(R.string.batch_correction_preview)).performScrollTo().performClick()
        compose.waitUntil(5000) { writes.any { it.first.endsWith("preview/") } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.batch_correction_after) + ": Hello world").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun previewDoesNotWriteAndChangedInputInvalidatesConfirmation() {
        prepare()
        assertEquals(1, writes.size)
        compose.onNodeWithText(label(R.string.batch_correction_find)).performScrollTo().performTextReplacement("Hello")
        compose.onNodeWithText(label(R.string.batch_correction_confirm)).assertIsNotEnabled()
    }
    @Test fun lostResponseRetriesTheSameRequest() {
        prepare(); status = 503
        compose.onNodeWithText(label(R.string.batch_correction_confirm)).performClick()
        compose.waitUntil(5000) { writes.size == 2 }
        compose.waitForIdle(); status = 200
        compose.onNodeWithText(label(R.string.batch_correction_retry)).performClick()
        compose.waitUntil(5000) { changed == 1 }
        assertEquals(writes[1], writes[2])
    }
    @Test fun conflictRequiresAnotherPreview() {
        prepare(); status = 409
        compose.onNodeWithText(label(R.string.batch_correction_confirm)).performClick()
        compose.waitUntil(5000) { writes.size == 2 }
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.batch_correction_conflict)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.batch_correction_confirm)).assertIsNotEnabled()
        assertEquals(0, changed)
    }
    @Test fun undoRequiresConfirmationAndTargetsTheReceipt() {
        showHistory = true; show()
        compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.batch_correction_undo)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.batch_correction_undo)).performScrollTo().performClick()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText(label(R.string.batch_correction_confirm_undo)).performClick()
        compose.waitUntil(5000) { changed == 1 }
        assertTrue(writes.single().first.endsWith("/$batch/undo/"))
    }
    @Test fun encryptedRetrySurvivesRepositoryRecreationAndIsIsolatedByAccount() = runBlocking {
        val intent = ReplacementConfirmation(UUID.randomUUID().toString(), "word", "world", hash)
        status = 503
        assertTrue(repository(true).applyReplacement("batch26-owner", record, intent).isFailure)
        val restored = repository(true).pendingReplacement("batch26-owner", record).getOrThrow()!!
        assertEquals("word", restored.find)
        assertNull(repository(true) { "batch26-other" }.pendingReplacement("batch26-other", record).getOrThrow())
        status = 403
        assertTrue(repository(true).applyReplacement("batch26-owner", record, restored).isFailure)
        assertNotNull(repository(true).pendingReplacement("batch26-owner", record).getOrThrow())
        status = 200
        assertTrue(repository(true).applyReplacement("batch26-owner", record, restored).isSuccess)
        assertEquals(writes[0], writes[1]); assertEquals(writes[1], writes[2])
        assertNull(repository(true).pendingReplacement("batch26-owner", record).getOrThrow())
    }
}
