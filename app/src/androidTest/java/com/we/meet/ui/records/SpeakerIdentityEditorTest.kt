package com.we.meet.ui.records

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Fixture-only UI plus the real Retrofit/repository path; never contact a live backend. */
@RunWith(AndroidJUnit4::class)
class SpeakerIdentityEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val record = "11111111-1111-4111-8111-111111111111"
    private val speakerId = "22222222-2222-4222-8222-222222222222"
    private val memberId = "33333333-3333-4333-8333-333333333333"
    private val secondMemberId = "44444444-4444-4444-8444-444444444444"
    private val departmentId = "55555555-5555-4555-8555-555555555555"
    private val speaker = RecordSpeakerDto(speakerId, "Speaker 1", "diarized", displayName = "Speaker 1", canAttribute = true,
        activity = RecordSpeakerActivityDto("recognized_speaker_time", "available", 10000, 100.0,
            RecordSpeakerTimelineDto("recognized_extent", "available", extentMs = 10000,
                intervals = listOf(RecordSpeechIntervalDto(3000, 10000)))))

    private inner class Fixture(val conflict: Boolean = false) {
        val posts = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger()
        var action = ""
        val repository: MeetingRecordRepository
        init {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                if (request.method == "POST") { posts += body; action = if (body.contains("set_label")) "custom" else if (body.contains("clear")) "none" else if (body.contains("member:")) "member" else "contact" }
                val json = when {
                    request.url.queryParameter("kind") == "departments" -> """{"results":[{"ref":"$departmentId","kind":"department","name":"Design","department_id":"$departmentId"}],"next_offset":null}"""
                    request.url.queryParameter("department_id") == departmentId -> {
                        val second = request.url.queryParameter("offset") == "25"
                        """{"results":[{"ref":"member:${if (second) secondMemberId else memberId}","kind":"member","name":"${if (second) "Ada Second" else "Ada Design"}","department_name":"Design","department_id":"$departmentId"}],"next_offset":${if (second) "null" else "25"}}"""
                    }
                    request.url.encodedPath.endsWith("speaker-contacts/") -> """{"results":[{"ref":"external:$memberId","kind":"external","name":"Ada Partner","organization_name":"Partner"}],"next_offset":null}"""
                    request.method == "POST" -> """{"id":"$speakerId","label":"Speaker 1","identity_type":"diarized","manual_label":"${if (action == "custom") "Guest" else if (action == "contact") "Ada Partner" else ""}","attribution_kind":"$action","record_revision":4,"attributed_user_id":${if (action == "member") "\"$secondMemberId\"" else "null"}}"""
                    else -> """{"id":"$record","source_type":"upload","title":"Fixture","origin_at":"2026-10-10T00:00:00Z","revision":${if (posts.isEmpty() || conflict) 3 else 4},"capabilities":{"read_transcript":true}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (conflict && request.method == "POST") 409 else 200).message("Fixture")
                    .body(json.toResponseBody()).build()
            }.build()
            val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(MeetingRecordApi::class.java)
            repository = MeetingRecordRepository(api) { "editor" }
        }
    }

    @Test fun readOnlyAndUnknownRowsOfferNoIdentityEditor() {
        val fixture = Fixture()
        compose.setContent { WeMeetTheme { Column {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker.copy(canAttribute = false), {})
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker.copy(identityType = "unknown"), {})
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).assertDoesNotExist()
        assertEquals(0, fixture.calls.get())
    }

    @Test fun customLabelSavesThroughTheCanonicalIdentityApiAndRefreshes() {
        val fixture = Fixture()
        val refreshes = AtomicInteger()
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker, { refreshes.incrementAndGet() })
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.onNodeWithText(text(R.string.speaker_identity_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  Guest  ")
        compose.onNodeWithText(text(R.string.records_correction_save)).performScrollTo().performClick()
        compose.waitUntil(10000) { refreshes.get() == 1 }
        assertEquals("""{"action":"set_label","expected_revision":3,"label":"Guest"}""", fixture.posts.single())
        compose.onNodeWithText(text(R.string.speaker_identity_current, "Speaker 1")).assertDoesNotExist()
    }

    @Test fun externalNamePreviewRequiresSaveAndExplainsSharing() {
        val fixture = Fixture()
        val refreshes = AtomicInteger()
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker, { refreshes.incrementAndGet() })
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Ada Partner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ada Partner").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.speaker_identity_external_hint)).performScrollTo().assertIsDisplayed()
        capture("contacts-external-light.png")
        assertTrue(fixture.posts.isEmpty())
        compose.onNodeWithText(text(R.string.records_correction_save)).performScrollTo().performClick()
        compose.waitUntil(10000) { refreshes.get() == 1 }
        assertEquals("""{"action":"select_contact","expected_revision":3,"contact_ref":"external:$memberId"}""", fixture.posts.single())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun conflictPreservesDraftAndDisablesSaveAndClear() {
        val fixture = Fixture(conflict = true)
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker, {})
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.onNodeWithText(text(R.string.speaker_identity_label)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Guest")
        compose.onNodeWithText(text(R.string.records_correction_save)).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.speaker_identity_conflict)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.speaker_identity_conflict)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guest").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Guest").performTextInputSelection(TextRange(0, 5))
        compose.onNodeWithText("Guest").performSemanticsAction(SemanticsActions.CopyText)
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("Guest", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithText(text(R.string.records_correction_save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.records_attribution_clear)).assertIsNotEnabled()
        assertEquals(1, fixture.posts.size)
    }

    @Test fun clearingExistingLabelUsesAnEmptyCanonicalDecision() {
        val fixture = Fixture()
        val refreshes = AtomicInteger()
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3,
                speaker.copy(displayName = "Guest", manualLabel = "Guest", attributionKind = "custom"), { refreshes.incrementAndGet() })
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.onNodeWithText(text(R.string.records_attribution_clear)).performScrollTo().performClick()
        compose.waitUntil(10000) { refreshes.get() == 1 }
        assertEquals("""{"action":"clear","expected_revision":3}""", fixture.posts.single())
    }

    @Test fun departmentFilterAndPaginationUseTheNewlySelectedMember() {
        val fixture = Fixture()
        val refreshes = AtomicInteger()
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker, { refreshes.incrementAndGet() })
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.onNodeWithText(text(R.string.speaker_identity_departments)).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Design").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Design").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Ada Design").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ada Design").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.records_next)).performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Ada Second").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.records_correction_save)).assertIsNotEnabled()
        compose.onNodeWithText("Ada Second").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.records_correction_save)).performScrollTo().performClick()
        compose.waitUntil(10000) { refreshes.get() == 1 }
        assertEquals("""{"action":"select_contact","expected_revision":3,"contact_ref":"member:$secondMemberId"}""", fixture.posts.single())
    }

    @Test fun sourcePreviewSeeksTheFirstVerifiedInterval() {
        val fixture = Fixture()
        var sought: Long? = null
        compose.setContent { WeMeetTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AttributableSpeakerRow(fixture.repository, "editor", record, 3, speaker, {}, onSource = { sought = it })
        } } }
        compose.onNodeWithText(text(R.string.speaker_identity_mark)).performClick()
        compose.onNodeWithText(text(R.string.speaker_identity_listen, "0:03")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3000L, sought) }
    }

    @Test fun chineseLargeTextAndDarkModeRemainScrollable() {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        val controller = SpeakerIdentityController(object : SpeakerIdentityOperations {
            override suspend fun contacts(query: String?, kind: String, departmentId: String?, offset: Int) = Result.success(RecordSpeakerContactPageDto())
            override suspend fun decide(request: RecordIdentityDecisionRequest) = Result.success(speaker)
        }, 3, initialLabel = "客户王经理")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                WeMeetTheme(darkTheme = true) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SpeakerIdentityEditor(controller, speaker, onRefresh = {})
                } }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.records_correction_save)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(localized.getString(R.string.records_attribution_clear)).assertIsDisplayed()
        capture("label-zh-dark-large.png")
    }

    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "speaker-identity").apply { mkdirs() }
        File(directory, name).outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
