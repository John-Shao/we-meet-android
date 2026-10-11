package com.we.meet.ui.records

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.SpeakerIdentificationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.auth.AuthInterceptor
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.repository.SpeakerIdentificationRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Actual Compose + Retrofit/repository, local fixtures; no live account or provider. */
@RunWith(AndroidJUnit4::class)
class SpeakerIdentificationPanelTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var resources: Context? = null
    private var diagnostic: Fixture? = null
    private fun text(id: Int, vararg args: Any) = (resources ?: context).getString(id, *args)
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val recordId = "22222222-2222-4222-8222-222222222222"
    private val organization = "44444444-4444-4444-8444-444444444444"
    private val member = "55555555-5555-4555-8555-555555555555"
    private val speakers = listOf("33333333-3333-4333-8333-333333333331", "33333333-3333-4333-8333-333333333332")
    private val names = listOf("Synthetic Reviewer", "Synthetic Participant")
    private val record = RecordDto(recordId, "upload", "Synthetic", "2026-10-10T00:00:00Z", 1,
        RecordCapabilitiesDto(edit = true, readTranscript = true, playMedia = true), upload = RecordUploadDto(status = "succeeded"))
    private inner class Fixture {
        @Volatile var session = "first-login"
        @Volatile var enabled = true
        @Volatile var conflict = false
        @Volatile var unavailable = false
        @Volatile var uncertain = false
        val calls = CopyOnWriteArrayList<String>()
        val submissions = CopyOnWriteArrayList<String>()
        val decisions = CopyOnWriteArrayList<String>()
        val changed = AtomicInteger()
        val stopped = AtomicInteger()
        val preview = CopyOnWriteArrayList<Pair<Long, Long>>()
        private var revision = 1
        private var batch: IdentityBatchDto? = null
        private val attributed = mutableSetOf<String>()
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request(); requirePrivateLogin(request, session)
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            synchronized(this) {
                calls += request.url.encodedPath
                val path = request.url.encodedPath
                var status = 200
                var value: Any = mapOf("code" to "fixture_missing")
                when {
                    path.endsWith("/config/") -> value = SpeakerIdentityConfigDto(SpeakerIdentityFlagsDto(enabled, enabled))
                    path.endsWith("speaker-identification-options/") -> value = IdentityOptionsDto(revision, null, true,
                        speakers.filterNot { it in attributed }.map { IdentityPersonDto(it, "Speaker ${speakers.indexOf(it)}") },
                        IdentityPageDto(listOf(IdentityScopeDto(organization, "Synthetic organization", true)), null))
                    path.endsWith("speaker-identification-candidates/") -> {
                        val personal = request.url.queryParameter("organization_id") == "personal"
                        value = IdentityCandidatesDto(revision, if (personal) null else organization,
                            if (personal) listOf(IdentityPersonDto(owner, names[0])) else listOf(IdentityPersonDto(owner, names[0]), IdentityPersonDto(member, names[1])), null)
                    }
                    path.endsWith("/speaker-identification/") && request.method == "POST" -> {
                        submissions += body
                        if (uncertain) throw IOException("Synthetic uncertain response")
                        val input = requireNotNull(moshi.adapter(IdentitySubmissionDto::class.java).fromJson(body))
                        assertEquals(revision, input.expectedRevision)
                        assertEquals(setOf(owner, member), input.userIds.toSet())
                        assertEquals(organization, input.organizationId)
                        batch = IdentityBatchDto(organization, input.requestKey, organization, revision, "2026-10-10T00:00:00Z", true,
                            input.speakerIds.mapIndexed { index, id -> IdentityJobDto("66666666-6666-4666-8666-66666666666${index + 1}", id, "queued", false, null) })
                        status = 202; value = IdentityResponseDto(revision, batch)
                    }
                    path.endsWith("/speaker-identification/") && request.method == "DELETE" -> {
                        batch = batch?.copy(processing = false, jobs = batch!!.jobs.map { it.copy(status = "canceled", suggestion = null) })
                        value = IdentityResponseDto(revision, batch)
                    }
                    path.endsWith("/speaker-identification/") -> {
                        if (unavailable) { status = 503; value = mapOf("code" to "voiceprint_matching_disabled") }
                        else if (batch == null && request.url.queryParameter("request_key") != null) { status = 404; value = mapOf("code" to "voiceprint_identity_request_unavailable") }
                        else {
                            batch = batch?.copy(processing = false, jobs = batch!!.jobs.mapIndexed { index, job ->
                                if (job.suggestion != null) job else job.copy(status = "succeeded", suggestion = IdentitySuggestionDto(
                                    "77777777-7777-4777-8777-77777777777${index + 1}", "pending", "suggested", "all_clips_agree", 3, 12000,
                                    listOf(IdentityIntervalDto(0, 4000), IdentityIntervalDto(10000, 14000), IdentityIntervalDto(20000, 24000)),
                                    true, false, IdentityPersonDto(if (index == 0) owner else member, names[index])))
                            })
                            value = IdentityResponseDto(revision, batch)
                        }
                    }
                    path.endsWith("/identity-decision/") -> {
                        if (conflict) { conflict = false; revision++; status = 409; value = mapOf("code" to "identity_revision_changed") }
                        else {
                            val input = requireNotNull(moshi.adapter(IdentitySuggestionDecisionDto::class.java).fromJson(body))
                            assertEquals(revision, input.expectedRevision)
                            val speaker = path.split('/')[6]
                            val job = requireNotNull(batch).jobs.single { it.speakerId == speaker }
                            val suggestion = requireNotNull(job.suggestion)
                            assertEquals(suggestion.id, input.suggestionId)
                            val confirm = input.action == "confirm_suggestion"
                            decisions += body; revision++
                            if (confirm) attributed += speaker
                            batch = batch!!.copy(jobs = batch!!.jobs.map { if (it.id != job.id) it else it.copy(suggestion = suggestion.copy(
                                state = if (confirm) "confirmed" else "rejected", canConfirm = false, candidate = null, queryIntervals = emptyList())) })
                            value = RecordSpeakerDto(speaker, "Speaker ${speakers.indexOf(speaker)}", "diarized", displayName = suggestion.candidate?.name ?: "Speaker",
                                attributedUserId = if (confirm) suggestion.candidate?.id else null, attributionKind = if (confirm) "member" else "none", recordRevision = revision)
                        }
                    }
                }
                if (!path.endsWith("/config/")) assertEquals(owner, request.header("X-Voiceprint-Owner"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .body(moshi.adapter(Any::class.java).toJson(value).toResponseBody("application/json".toMediaType())).build()
            }
        }.build()
        val repository = SpeakerIdentificationRepository(Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build().create(SpeakerIdentificationApi::class.java), { owner }, { session })
    }
    private fun show(fixture: Fixture, current: RecordDto = record, chineseDarkLarge: Boolean = false,
        captureDerivation: androidx.compose.runtime.State<String?> = androidx.compose.runtime.mutableStateOf(null)) {
        diagnostic = fixture
        val configuration = Configuration(context.resources.configuration).apply { if (chineseDarkLarge) setLocale(Locale.SIMPLIFIED_CHINESE) }
        resources = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides requireNotNull(resources), LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, if (chineseDarkLarge) 1.5f else 1f)) {
                WeMeetTheme(darkTheme = chineseDarkLarge) { Surface { Column {
                    RecordSpeakerIdentification(fixture.repository, owner, current, { fixture.changed.incrementAndGet() },
                        { start, end -> fixture.preview += start to end }, { fixture.stopped.incrementAndGet() }, captureDerivation.value)
                } } }
            }
        }
    }
    private fun waitText(value: String) {
        // Opening performs three sequential private reads; allow the isolated
        // emulator's cold Retrofit/Moshi initialization without relaxing assertions.
        try { compose.waitUntil(30000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            val directory = File(context.getExternalFilesDir(null), "speaker-identification").apply { mkdirs() }
            File(directory, "failure.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val nodes = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            val tree = nodes.indices.joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }
            throw AssertionError("Missing '$value'; fixture routes: ${diagnostic?.calls}\n$tree", error)
        }
    }
    private fun openAndChoose(fixture: Fixture) {
        waitText(text(R.string.identity_open)); compose.onNodeWithText(text(R.string.identity_open)).performClick()
        waitText("Synthetic organization"); compose.onNodeWithText("Synthetic organization").performScrollTo().performClick()
        waitText(names[1])
        compose.onNodeWithText(names[0]).performScrollTo().performClick()
        compose.onNodeWithText(names[1]).performScrollTo().performClick()
        assertTrue(fixture.submissions.isEmpty())
    }
    private fun submit(fixture: Fixture) {
        openAndChoose(fixture)
        waitText(text(R.string.identity_submit))
        compose.onNodeWithText(text(R.string.identity_submit)).performScrollTo().assertIsEnabled().performClick()
        waitText(text(R.string.identity_suggested, names[0]))
    }
    @Test fun closedPanelOnlyReadsConfigAndReadonlyHasNoPrivateRequests() {
        val fixture = Fixture(); show(fixture)
        waitText(text(R.string.identity_open))
        assertTrue(fixture.calls.all { it.endsWith("/config/") })
        assertTrue(fixture.submissions.isEmpty())
    }
    @Test fun recordingWithoutSuccessfulDiarizationHasNoPrivateEntry() {
        val fixture=Fixture(); show(fixture,record.copy(sourceType="audio_recording",upload=null))
        compose.onNodeWithText(text(R.string.identity_open)).assertDoesNotExist()
        assertTrue(fixture.calls.isEmpty())
    }
    @Test fun publishedRecordingSupportsReviewAndChangingGenerationClearsOldChoices() {
        val fixture=Fixture()
        val generation=androidx.compose.runtime.mutableStateOf<String?>("66666666-6666-4666-8666-666666666666")
        show(fixture,record.copy(sourceType="audio_recording",upload=null),captureDerivation=generation)
        openAndChoose(fixture)
        val stopped=fixture.stopped.get()
        compose.runOnIdle { generation.value="77777777-7777-4777-8777-777777777777" }
        compose.waitUntil(10000) { fixture.stopped.get() > stopped }
        compose.onNodeWithText(text(R.string.identity_submit)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.identity_open)).assertExists()
        assertTrue(fixture.submissions.isEmpty())
    }
    @Test fun readonlyOrMissingEditCapabilityHidesTheEntry() {
        val fixture = Fixture(); show(fixture, record.copy(capabilities = record.capabilities.copy(edit = false)))
        compose.onNodeWithText(text(R.string.identity_open)).assertDoesNotExist()
        assertTrue(fixture.calls.isEmpty())
    }
    @Test fun serverDisabledMatchingHidesTheEntryWithoutReadingADirectory() {
        val fixture = Fixture(); fixture.enabled = false; show(fixture)
        compose.waitUntil(10000) { fixture.calls.isNotEmpty() }
        compose.onNodeWithText(text(R.string.identity_open)).assertDoesNotExist()
        assertTrue(fixture.calls.all { it.endsWith("/config/") })
    }
    @Test fun explicitCandidatesPreviewAndTwoDecisionsRecoverFromAConflict() {
        val fixture = Fixture(); show(fixture); submit(fixture)
        compose.onAllNodesWithText(text(R.string.identity_preview, 1))[0].performScrollTo().performClick()
        assertEquals(0L to 4000L, fixture.preview.single())
        compose.onAllNodesWithText(text(R.string.identity_confirm))[0].performScrollTo().performClick()
        compose.waitUntil(10000) { fixture.changed.get() == 1 }
        waitText(text(R.string.identity_suggested, names[1]))
        fixture.conflict = true
        compose.onNodeWithText(text(R.string.identity_confirm)).performScrollTo().performClick()
        waitText(text(R.string.identity_conflict))
        compose.onNodeWithText(text(R.string.identity_suggested, names[1])).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.records_refresh)).performScrollTo().performClick()
        waitText(text(R.string.identity_suggested, names[1]))
        compose.onNodeWithText(text(R.string.identity_confirm)).performScrollTo().performClick()
        compose.waitUntil(10000) { fixture.changed.get() == 2 }
        assertEquals(1, fixture.submissions.size); assertEquals(2, fixture.decisions.size)
        assertTrue(fixture.stopped.get() >= 3)
    }
    @Test fun anUncertainSubmissionUsesItsOriginalCommandAfterClosingAndReopening() {
        val fixture = Fixture(); fixture.uncertain = true; show(fixture); openAndChoose(fixture)
        compose.onNodeWithText(text(R.string.identity_submit)).performScrollTo().performClick()
        waitText(text(R.string.identity_uncertain))
        compose.onNodeWithText(text(R.string.records_close)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.identity_open)).performClick()
        waitText(text(R.string.identity_retry_same)); fixture.uncertain = false
        compose.onNodeWithText(text(R.string.identity_retry_same)).performScrollTo().performClick()
        waitText(text(R.string.identity_suggested, names[0]))
        assertEquals(2, fixture.submissions.size); assertEquals(fixture.submissions[0], fixture.submissions[1])
    }
    @Test fun pendingNamesAreHiddenAfterAReadFailureAndCancellationRemainsAvailable() {
        val fixture = Fixture(); show(fixture); submit(fixture); fixture.unavailable = true
        waitText(text(R.string.identity_request_error))
        compose.onNodeWithText(text(R.string.identity_suggested, names[0])).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.identity_cancel)).performScrollTo().assertIsEnabled().performClick()
        waitText(text(R.string.identity_canceled))
    }
    @Test fun sameAccountReloginClosesTheSheetAndDropsAllPrivateNames() {
        val fixture = Fixture(); show(fixture); submit(fixture); fixture.session = "second-login"
        compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.identity_title)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.identity_suggested, names[0])).assertDoesNotExist()
        assertEquals(1, fixture.submissions.size)
    }
    @Test fun chineseDarkLargeTextKeepsSelectionAndDecisionsReachable() {
        val fixture = Fixture(); show(fixture, chineseDarkLarge = true); submit(fixture)
        compose.onAllNodesWithText(text(R.string.identity_confirm))[0].performScrollTo().assertIsDisplayed().assertIsEnabled()
        val directory = File(context.getExternalFilesDir(null), "speaker-identification").apply { mkdirs() }
        File(directory, "suggestions-zh-dark-large.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun queuedRequestCannotAttachCredentialsFromASameAccountRelogin() = runBlocking {
        val tokens = TokenStore(context)
        tokens.clear(); tokens.userId = owner; tokens.accessToken = "synthetic-first-login"
        val terminalCalls = AtomicInteger()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                tokens.clear(); tokens.userId = owner; tokens.accessToken = "synthetic-second-login"
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthInterceptor(tokens))
            .addInterceptor { chain -> terminalCalls.incrementAndGet(); throw AssertionError("Old request reached transport: " + chain.request().url.encodedPath) }
            .build()
        try {
            val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(SpeakerIdentificationApi::class.java)
            val repository = SpeakerIdentificationRepository(api, { tokens.userId }, { tokens.authSnapshot().session })
            assertTrue(repository.open(owner, recordId).read().isFailure)
            assertEquals(0, terminalCalls.get())
        } finally { tokens.clear() }
    }

    @Test fun normalTokenRotationPreservesTheOriginalPrivateLogin() = runBlocking {
        val tokens = TokenStore(context)
        tokens.clear(); tokens.userId = owner; tokens.accessToken = "synthetic-first-login"
        val original = tokens.authSnapshot()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor { chain -> assertTrue(tokens.rotate(original, "synthetic-refreshed", null)); chain.proceed(chain.request()) }
            .addInterceptor(AuthInterceptor(tokens))
            .addInterceptor { chain ->
                assertEquals("Bearer synthetic-refreshed", chain.request().header("Authorization"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("""{"record_revision":1,"request":null}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
        try {
            val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(SpeakerIdentificationApi::class.java)
            val repository = SpeakerIdentificationRepository(api, { tokens.userId }, { tokens.authSnapshot().session })
            assertTrue(repository.open(owner, recordId).read().isSuccess)
            assertEquals(original.session, tokens.authSnapshot().session)
        } finally { tokens.clear() }
    }
}
