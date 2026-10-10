package com.we.meet.ui.voiceprint

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.VoiceprintApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.VoiceprintRepository
import com.we.meet.data.voiceprint.VoiceprintRecording
import com.we.meet.data.voiceprint.VoiceprintAudioOutput
import com.we.meet.data.voiceprint.VoiceprintWave
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

/** Real screen/repository/Retrofit and native playback, fake recording input only. */
@RunWith(AndroidJUnit4::class)
class VoiceprintSettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var resources: Context? = null
    private class ExternalResults : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { this.requestCode = requestCode }
    }
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val organization = "22222222-2222-4222-8222-222222222222"
    private val profile = "33333333-3333-4333-8333-333333333333"
    private val enrollment = "44444444-4444-4444-8444-444444444444"
    private val sample = "55555555-5555-4555-8555-555555555555"
    private val deletion = "77777777-7777-4777-8777-777777777777"
    private val expiry = "2099-10-10T00:00:00Z"
    private val token = "a".repeat(43)
    private val wav = VoiceprintWave.encode(ShortArray(72000) { (it % 97).toShort() })
    private fun text(id: Int, vararg args: Any) = (resources ?: context).getString(id, *args)
    private inner class Fixture {
        @Volatile var session = "synthetic-login"
        @Volatile var available = true
        @Volatile var allowEnrollment = true
        @Volatile var allowAccumulation = false
        @Volatile var allowIdentification = false
        @Volatile var conflict = false
        @Volatile var uncertain = false
        @Volatile var hasSample = false
        var administrator = false
        var policyEnabled = true
        var policyVersion = 0
        var version = 1
        var sampleStatus = "quality_pending"
        var slots = emptyList<Int>()
        var deleted = false
        var accepted = false
        val calls = CopyOnWriteArrayList<String>()
        val posts = CopyOnWriteArrayList<Pair<String, String>>()
        val uploads = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val recordings = AtomicInteger()
        val audioReads = AtomicInteger()
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private fun settings(scope: String?) = VoiceprintSettingsDto(scope, available && (scope == null || policyEnabled), version, 1, allowEnrollment, allowAccumulation, allowIdentification,
            listOf(VoiceprintProfileDto(profile, if (deleted) "deleted" else if (accepted) "active" else "pending", 1, null, null)))
        private fun registration(scope: String?) = VoiceprintEnrollmentDto(enrollment, scope, profile, if (slots.size == 6) "closed" else "open", expiry, version,
            1, (0..5).map { "Synthetic randomized prompt $it" }, 6, slots, 24000, 1, "pcm16_wav", VoiceprintDurationDto(3000, 10000), if (slots.size == 6) null else token)
        private fun clip() = VoiceprintSampleDto(sample, profile, sampleStatus, "enrollment", 3000, expiry,
            sampleStatus == "ready", sampleStatus !in listOf("rejected", "deleted", "expired"))
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request(); requirePrivateLogin(request, session)
            assertEquals(owner, request.header("X-Voiceprint-Owner"))
            val bytes = request.body?.let { Buffer().also(it::writeTo).readByteArray() } ?: byteArrayOf()
            synchronized(this) {
                val path = request.url.encodedPath
                calls += request.method + " " + path
                val scope = request.url.queryParameter("organization_id")
                val input = if (bytes.isNotEmpty() && request.method != "PUT") moshi.adapter(Any::class.java).fromJson(bytes.toString(Charsets.UTF_8)) as Map<*, *> else emptyMap<String, Any>()
                var code = 200; var value: Any = mapOf("code" to "fixture_missing")
                if (request.method in listOf("POST", "PATCH", "DELETE")) posts += request.method to bytes.toString(Charsets.UTF_8)
                when {
                    path.endsWith("/scopes/") -> value = VoiceprintPageDto(listOf(VoiceprintScopeDto(organization, "Synthetic organization", administrator, VoiceprintPolicyDto(policyEnabled, policyVersion))), null)
                    path.endsWith("/organizations/$organization/settings/") && request.method == "PATCH" -> {
                        assertTrue(administrator); assertEquals(policyVersion.toDouble(), input["expected_version"])
                        policyEnabled = input["enabled"] == true; policyVersion++
                        value = VoiceprintPolicyDto(policyEnabled, policyVersion)
                    }
                    path.endsWith("/settings/") && request.method == "GET" -> value = settings(scope)
                    path.endsWith("/settings/") && request.method == "PATCH" -> {
                        assertEquals(version.toDouble(), input["expected_version"])
                        if (conflict) { conflict = false; version++; code = 409; value = mapOf("code" to "voiceprint_version_changed") }
                        else {
                            version++
                            if ("allow_enrollment" in input) allowEnrollment = input["allow_enrollment"] == true
                            if ("allow_accumulation" in input) allowAccumulation = input["allow_accumulation"] == true
                            if ("allow_identification" in input) allowIdentification = input["allow_identification"] == true
                            value = settings(input["organization_id"] as String?)
                        }
                    }
                    path.endsWith("/enrollments/") && request.method == "POST" -> { code = 201; value = registration(input["organization_id"] as String?) }
                    path.endsWith("/enrollments/$enrollment/") -> value = registration(if (posts.any { it.second.contains(organization) }) organization else null)
                    request.method == "PUT" -> {
                        assertEquals("audio/wav", request.body!!.contentType().toString()); assertEquals(token, request.header("X-Voiceprint-Upload-Token"))
                        assertEquals(bytes.size.toLong(), request.body!!.contentLength()); assertArrayEquals(wav, bytes)
                        uploads += path to bytes.copyOf(); hasSample = true
                        val slot = path.trimEnd('/').substringAfterLast('/').toInt()
                        slots = (slots + slot).distinct()
                        if (uncertain) { uncertain = false; throw IOException("Synthetic unknown response") }
                        code = 202; value = clip()
                    }
                    path.endsWith("/samples/") -> value = VoiceprintPageDto(if (hasSample && !deleted) listOf(clip()) else emptyList(), null)
                    path.endsWith("/samples/$sample/") -> value = clip()
                    path.endsWith("/audio/") -> { audioReads.incrementAndGet(); value = wav }
                    path.endsWith("/decision/") -> {
                        accepted = input["accepted"] == true
                        sampleStatus = if (accepted) "confirmed" else "rejected"
                        value = clip()
                    }
                    path.endsWith("/profiles/$profile/") -> { deleted = true; code = 202; value = VoiceprintDeletionDto(deletion, "queued", 1, null, null) }
                    path.endsWith("/deletions/") -> value = VoiceprintPageDto(if (deleted) listOf(VoiceprintDeletionDto(deletion, "queued", 1, null, null)) else emptyList(), null)
                }
                val jsonValue = if (value is VoiceprintPageDto<*>) mapOf("results" to value.results, "next_offset" to value.nextOffset) else value
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                    .body(if (value is ByteArray) value.toResponseBody("audio/wav".toMediaType()) else moshi.adapter(Any::class.java).toJson(jsonValue).toResponseBody("application/json".toMediaType())).build()
            }
        }.build()
        val repository = VoiceprintRepository(Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build().create(VoiceprintApi::class.java), { owner }, { session })
        fun recorder() = object : VoiceprintRecording {
            override suspend fun capture(allowed: () -> Boolean, onStarted: () -> Unit): ByteArray {
                assertTrue(allowed()); recordings.incrementAndGet(); onStarted(); return wav.copyOf()
            }
            override fun finish() {}
            override fun cancel() {}
        }
    }
    private fun show(fixture: Fixture, chinese: Boolean = false, results: ActivityResultRegistryOwner = compose.activity) {
        val configuration = Configuration(context.resources.configuration).apply { if (chinese) setLocale(Locale.SIMPLIFIED_CHINESE) }
        resources = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides requireNotNull(resources), LocalConfiguration provides configuration,
                LocalActivityResultRegistryOwner provides results,
                LocalDensity provides Density(LocalDensity.current.density, if (chinese) 1.5f else 1f)) {
                WeMeetTheme(darkTheme = chinese) { VoiceprintSettingsScreen(fixture.repository, owner, {}, { fixture.recorder() }) }
            }
        }
        waitText(text(R.string.voiceprint_begin))
    }
    private fun waitText(value: String) {
        try { compose.waitUntil(30000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            val directory = File(context.getExternalFilesDir(null), "voiceprint-settings").apply { mkdirs() }
            File(directory, "failure.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw AssertionError("Missing '$value'\n" + compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }, error)
        }
    }
    private fun click(id: Int) { compose.onNodeWithText(text(id)).performScrollTo().assertIsEnabled().performClick() }
    private fun permission(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
    private fun record(fixture: Fixture) {
        click(R.string.voiceprint_begin); waitText("Synthetic randomized prompt ${fixture.slots.size}")
        click(R.string.voiceprint_record); waitText(text(R.string.voiceprint_local_audio))
        assertEquals(1, fixture.recordings.get())
    }
    @Test fun openingOnlyReadsAndDoesNotRegisterRecordOrLoadSampleAudio() {
        val fixture = Fixture(); show(fixture)
        assertTrue(fixture.posts.isEmpty()); assertTrue(fixture.uploads.isEmpty()); assertEquals(0, fixture.recordings.get()); assertEquals(0, fixture.audioReads.get())
        compose.onNodeWithText(text(R.string.voiceprint_organization_policy)).assertDoesNotExist()
    }
    @Test fun stoppingNativePlaybackConcurrentlyWithCompletionChecksNeverReadsAReleasedTrack() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val output = VoiceprintAudioOutput(context) { }
        output.play(wav)
        val workers = (0..2).map { worker ->
            Thread {
                try { repeat(2000) { if (worker == 0) output.stop() else assertFalse(output.completed()) } }
                catch (error: Throwable) { errors += error }
            }
        }
        try {
            workers.forEach { it.start() }; workers.forEach { it.join(5000) }
            assertTrue(workers.none { it.isAlive }); assertTrue(errors.joinToString(), errors.isEmpty())
        } finally { output.stop() }
    }
    @Test fun returningFromTheSystemFilePickerRestoresFreshAuthorityAndRequiresExplicitUpload() {
        val fixture = Fixture(); val results = ExternalResults(); show(fixture, results = results)
        click(R.string.voiceprint_begin); waitText("Synthetic randomized prompt 0")
        click(R.string.voiceprint_file); compose.waitUntil(10000) { results.requestCode != null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val file = File(context.cacheDir, "synthetic-voiceprint-picker.wav")
        try {
            file.writeBytes(wav)
            compose.runOnUiThread { results.dispatchResult(requireNotNull(results.requestCode), Uri.fromFile(file)) }
            assertTrue(fixture.uploads.isEmpty()); assertEquals(0, fixture.recordings.get())
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitText(text(R.string.voiceprint_local_audio))
            assertTrue(fixture.uploads.isEmpty()); assertEquals(1, fixture.posts.count { it.first == "POST" })
            click(R.string.voiceprint_upload); compose.waitUntil(10000) { fixture.uploads.size == 1 }
        } finally { file.delete() }
    }
    @Test fun grantingMicrophonePermissionAfterPauseRevalidatesBeforeSyntheticRecording() {
        val fixture = Fixture(); val results = ExternalResults(); show(fixture, results = results)
        click(R.string.voiceprint_begin); waitText("Synthetic randomized prompt 0")
        click(R.string.voiceprint_record); compose.waitUntil(10000) { results.requestCode != null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnUiThread { results.dispatchResult(requireNotNull(results.requestCode), true) }
        assertEquals(0, fixture.recordings.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText(text(R.string.voiceprint_local_audio))
        assertEquals(1, fixture.recordings.get()); assertTrue(fixture.uploads.isEmpty())
    }
    @Test fun aPermissionChangeDoesNotImplicitlyGrantOtherPermissionsOrBeginRegistration() {
        val fixture = Fixture(); fixture.allowEnrollment = false; show(fixture)
        permission(R.string.voiceprint_permissions_allow_enrollment_title).performScrollTo().performClick()
        compose.waitUntil(10000) { fixture.allowEnrollment }
        assertFalse(fixture.allowAccumulation); assertFalse(fixture.allowIdentification)
        assertEquals(1, fixture.posts.size); assertEquals(0, fixture.recordings.get()); assertTrue(fixture.uploads.isEmpty())
        assertTrue(fixture.posts.single().second.contains("\"organization_id\":null"))
    }
    @Test fun syntheticRecordingIsLocallyPreviewedAndOnlyUploadedOnExplicitAction() {
        val fixture = Fixture(); show(fixture); record(fixture)
        assertTrue(fixture.uploads.isEmpty()); click(R.string.voiceprint_local_audio); click(R.string.voiceprint_upload)
        waitText(text(R.string.voiceprint_sample_status_quality_pending))
        assertEquals(1, fixture.uploads.size); assertTrue(fixture.uploads.single().first.endsWith("/clips/0/"))
        compose.onNodeWithText(text(R.string.voiceprint_confirm)).assertDoesNotExist()
    }
    @Test fun theLastUnknownUploadRetriesTheSameSlotAndBytesAfterTheServerClosesRegistration() {
        val fixture = Fixture(); fixture.slots = (0..4).toList(); fixture.uncertain = true; show(fixture); record(fixture)
        click(R.string.voiceprint_upload); waitText(text(R.string.voiceprint_errors_failed))
        compose.waitUntil(15000) { fixture.calls.count { it == "GET /api/v1.0/voiceprint/enrollments/$enrollment/" } >= 2 }
        compose.onNodeWithText(text(R.string.voiceprint_upload)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15000) { fixture.uploads.size == 2 }
        assertEquals(fixture.uploads[0].first, fixture.uploads[1].first); assertArrayEquals(fixture.uploads[0].second, fixture.uploads[1].second)
        assertTrue(fixture.uploads[0].first.endsWith("/clips/5/"))
    }
    @Test fun readyConfirmationRequiresCompleteNativePlaybackAndAnExplicitSelfAssertion() {
        val fixture = Fixture(); fixture.hasSample = true; fixture.sampleStatus = "ready"; show(fixture)
        click(R.string.voiceprint_listen); waitText(text(R.string.voiceprint_confirm))
        compose.onNodeWithText(text(R.string.voiceprint_confirm)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.voiceprint_self_confirmed)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.voiceprint_confirm)).assertIsNotEnabled()
        click(R.string.voiceprint_sample_audio)
        compose.waitUntil(15000) { !compose.onNodeWithText(text(R.string.voiceprint_confirm)).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        click(R.string.voiceprint_confirm); compose.waitUntil(10000) { fixture.accepted }
        assertEquals(1, fixture.audioReads.get()); assertEquals(1, fixture.posts.count { it.second.contains("\"accepted\":true") })
    }
    @Test fun qualityPendingSamplesCanBeRejectedWithoutPretendingTheyAreConfirmed() {
        val fixture = Fixture(); fixture.hasSample = true; show(fixture); click(R.string.voiceprint_reject)
        waitText(text(R.string.voiceprint_sample_status_rejected)); assertFalse(fixture.accepted); assertEquals(0, fixture.audioReads.get())
        assertEquals(1, fixture.posts.count { it.second.contains("\"accepted\":false") })
    }
    @Test fun disabledScopesAllowRevocationAndExplicitDeletionButNeverRegistration() {
        val fixture = Fixture(); fixture.available = false; show(fixture)
        compose.onNodeWithText(text(R.string.voiceprint_begin)).performScrollTo().assertIsNotEnabled()
        click(R.string.voiceprint_delete)
        waitText(text(R.string.voiceprint_confirm_delete)); compose.onNodeWithText(text(R.string.voiceprint_confirm_delete)).performClick()
        waitText(text(R.string.voiceprint_deletion_status_queued))
        assertTrue(fixture.deleted); assertEquals(1, fixture.posts.count { it.first == "DELETE" })
    }
    @Test fun sameAccountReloginDisposesThePrivateSettingsAndCannotUseOldControls() {
        val fixture = Fixture(); show(fixture); fixture.session = "second-login"
        waitText(text(R.string.voiceprint_login_required)); compose.onNodeWithText(text(R.string.voiceprint_begin)).assertDoesNotExist()
        assertTrue(fixture.posts.isEmpty())
    }
    @Test fun conflictRequiresManualReloadAndDoesNotReplayAConsentWrite() {
        val fixture = Fixture(); fixture.conflict = true; show(fixture)
        permission(R.string.voiceprint_permissions_allow_identification_title).performScrollTo().performClick()
        waitText(text(R.string.voiceprint_errors_conflict)); permission(R.string.voiceprint_permissions_allow_identification_title).assertIsNotEnabled()
        click(R.string.voiceprint_reload)
        compose.waitUntil(15000) { compose.onAllNodesWithText(text(R.string.voiceprint_errors_conflict)).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, fixture.posts.size); assertFalse(fixture.allowIdentification)
    }
    @Test fun chineseDarkLargeTextKeepsDeletionConfirmationReachable() {
        val fixture = Fixture(); show(fixture, chinese = true); click(R.string.voiceprint_delete)
        waitText(text(R.string.voiceprint_confirm_delete)); compose.onNodeWithText(text(R.string.voiceprint_confirm_delete)).assertIsDisplayed()
        val directory = File(context.getExternalFilesDir(null), "voiceprint-settings").apply { mkdirs() }
        File(directory, "delete-zh-dark-large.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun administratorPolicyOnlyOpensTheSelectedOrganizationAndDoesNotGrantConsent() {
        val fixture = Fixture(); fixture.administrator = true; fixture.policyEnabled = false; fixture.allowEnrollment = false
        show(fixture)
        compose.onNodeWithText("Synthetic organization").performScrollTo().performClick()
        waitText(text(R.string.voiceprint_organization_policy))
        compose.onNodeWithText(text(R.string.voiceprint_begin)).performScrollTo().assertIsNotEnabled()
        permission(R.string.voiceprint_organization_policy).performScrollTo().performClick()
        compose.waitUntil(10000) { fixture.policyEnabled }
        compose.waitUntil(10000) { compose.onAllNodesWithText(text(R.string.voiceprint_disabled)).fetchSemanticsNodes().isEmpty() }
        assertFalse(fixture.allowEnrollment); assertFalse(fixture.allowAccumulation); assertFalse(fixture.allowIdentification)
        assertEquals(1, fixture.posts.size)
        assertEquals("""{"enabled":true,"expected_version":0}""", fixture.posts.single().second)
        compose.onNodeWithText(text(R.string.voiceprint_begin)).performScrollTo().assertIsNotEnabled()
    }
}
