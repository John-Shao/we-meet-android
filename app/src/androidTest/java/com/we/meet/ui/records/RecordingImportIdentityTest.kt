package com.we.meet.ui.records

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.*
import com.we.meet.data.auth.requirePrivateLogin
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.data.voiceprint.VoiceprintWave
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Local synthetic bytes and in-memory HTTP responses only. Never opens a microphone or network socket. */
class RecordingImportIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ownerId = "11111111-1111-4111-8111-111111111111"
    private val memberId = "22222222-2222-4222-8222-222222222222"
    private val organizationId = "33333333-3333-4333-8333-333333333333"
    private val recordId = "44444444-4444-4444-8444-444444444444"

    private inner class Fixture(val context: Context = instrumentation.targetContext, val direct: Boolean = false) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
        @Volatile var session = "synthetic-login"
        @Volatile var matching = true
        @Volatile var state = RecordingUploadState(recordId, "failed", 1, identityPreflight = RecordingIdentityPreflight("awaiting_choice", canContinueWithoutIdentity = true))
        var selfName = "Synthetic self"
        var colleagueName = "Synthetic colleague"
        val organizationName = "Synthetic organization"
        val uploads = AtomicInteger()
        val puts = AtomicInteger()
        val completions = AtomicInteger()
        val decisions = CopyOnWriteArrayList<Map<*, *>>()
        val declarations = CopyOnWriteArrayList<String>()
        var navigated: String? = null
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private val retrofit = Retrofit.Builder().baseUrl("https://fixture.invalid/").validateEagerly(true)
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request(); requirePrivateLogin(request, session)
                val path = request.url.encodedPath
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                var code = 200
                val reply: Any = when {
                    path.endsWith("identity-candidates/") -> {
                        if (!matching) { code = 503; mapOf("code" to "unavailable") }
                        else {
                            val personal = request.url.queryParameter("organization_id") == "personal"
                            mapOf("organization_id" to if (personal) null else organizationId,
                                "results" to listOf(mapOf("id" to if (personal) ownerId else memberId, "name" to if (personal) selfName else colleagueName)), "next_offset" to null)
                        }
                    }
                    path.endsWith("voiceprint/scopes/") -> mapOf("results" to listOf(mapOf("id" to organizationId, "name" to organizationName,
                        "can_manage_policy" to false, "policy" to mapOf("enabled" to true, "version" to 1))), "next_offset" to null)
                    path.endsWith("identity-preflight/") -> {
                        assertEquals(ownerId, request.header("X-Voiceprint-Owner"))
                        val decision = moshi.adapter(Map::class.java).fromJson(body)!!; decisions += decision
                        assertEquals(1.0, decision["expected_attempt"])
                        state = RecordingUploadState(recordId, "queued", 2, identityPreflight = RecordingIdentityPreflight(
                            if (decision["action"] == "continue_without_identity") "disabled" else "pending"))
                        code = 202; state
                    }
                    path.endsWith("upload-url/") -> RecordingUploadTicket("https://storage.invalid/synthetic", "synthetic.wav",
                        mapOf("Content-Type" to moshi.adapter(Map::class.java).fromJson(body)!!["content_type"].toString()))
                    path.endsWith("upload-complete/") -> {
                        declarations += body
                        if (completions.incrementAndGet() == 1) { code = 503; mapOf("code" to "unavailable") }
                        else RecordingUploadState(recordId, "queued", 1)
                    }
                    path.endsWith("recording-uploads/") && request.method == "GET" -> RecordingUploadCapabilities(true, if (direct) 1 else 1_000_000,
                        listOf("wav"), direct, if (direct) 512L * 1024 * 1024 else 0,
                        identityPreflight = if (matching) RecordingIdentityCapability(true, maxCandidates = 2, maxBytes = 512L * 1024 * 1024, maxDurationMs = 7_200_000) else RecordingIdentityCapability(false))
                    path.endsWith("recording-uploads/") && request.method == "POST" -> {
                        assertEquals(ownerId, request.header("X-Voiceprint-Owner")); uploads.incrementAndGet(); declarations += body
                        RecordingUploadState(recordId, "queued", 1)
                    }
                    path.endsWith("$recordId/") && request.method == "GET" -> state
                    else -> error("Unexpected fixture route: ${request.method} $path")
                }
                Response.Builder().request(request).code(code).protocol(Protocol.HTTP_1_1).message("Synthetic")
                    .body(moshi.adapter(Any::class.java).serializeNulls().toJson(reply).toResponseBody("application/json".toMediaType())).build()
            }.build()).addConverterFactory(MoshiConverterFactory.create(moshi).withNullSerialization()).build()
        val repository = RecordingUploadRepository(retrofit.create(RecordingUploadApi::class.java), { ownerId },
            storage = RecordingStorage { _, headers, size, open ->
                assertFalse(headers.keys.any { it.equals("Authorization", true) || it.equals("X-Voiceprint-Owner", true) })
                assertEquals(size, open().use { it.readBytes().size.toLong() }); puts.incrementAndGet(); true
            }, importApi = retrofit.create(RecordingImportApi::class.java), currentSession = { session })
        init { instrumentation.runOnMainSync { lifecycle.currentState = Lifecycle.State.RESUMED } }
        fun label(id: Int) = context.getString(id)
        fun resume() {
            compose.runOnIdle { lifecycle.currentState = Lifecycle.State.STARTED }
            compose.waitForIdle()
            compose.runOnIdle { lifecycle.currentState = Lifecycle.State.RESUMED }
        }
    }
    private fun waitForLabel(f: Fixture, id: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(f.label(id)).fetchSemanticsNodes().isNotEmpty() }
    }
    @Suppress("DEPRECATION")
    private fun withImport(f: Fixture, large: Boolean = false, test: () -> Unit) {
        // Dialogs use the host Activity's resources, so localize that fixture too.
        // Restore its resources; never change the emulator's system locale or font settings.
        val activityResources = compose.activity.resources
        val originalConfiguration = Configuration(activityResources.configuration)
        if (large) instrumentation.runOnMainSync { activityResources.updateConfiguration(f.context.resources.configuration, activityResources.displayMetrics) }
        val resolver = f.context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "import-identity-synthetic.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav"); put(MediaStore.Audio.Media.IS_PENDING, 1)
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(VoiceprintWave.encode(ShortArray(VoiceprintWave.SAMPLE_RATE * 3) { index -> ((index % 120) * 50).toShort() })) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalLifecycleOwner provides f,
                    LocalContext provides f.context, LocalConfiguration provides f.context.resources.configuration,
                    LocalDensity provides Density(density.density, if (large) 1.8f else density.fontScale)) {
                    WeMeetTheme(darkTheme = large) { RecordingUploadAction(f.repository, ownerId, { f.navigated = it }, tile = true) }
                }
            }
            waitForLabel(f, R.string.records_upload)
            compose.onNodeWithText(f.label(R.string.records_upload)).performClick()
            waitForLabel(f, R.string.record_upload_title)
            test()
        } catch (failure: Throwable) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            File(f.context.cacheDir, "import-identity-failure.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            throw failure
        } finally {
            resolver.delete(uri, null, null)
            if (large) instrumentation.runOnMainSync { activityResources.updateConfiguration(originalConfiguration, activityResources.displayMetrics) }
        }
    }
    private fun enable(f: Fixture) {
        compose.onNodeWithContentDescription(f.label(R.string.import_identity_enable)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(f.selfName).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun choosingAnOrganizationClearsPersonalCandidatesAndSendsOnlyTheExplicitMember() {
        val f = Fixture(); withImport(f) {
            enable(f); compose.onNodeWithText(f.label(R.string.record_upload_submit)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(f.selfName).performScrollTo().performClick()
            compose.onNodeWithText(f.label(R.string.import_identity_personal)).performScrollTo().performClick()
            compose.onNodeWithText(f.organizationName).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(f.colleagueName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(f.label(R.string.record_upload_submit)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(f.colleagueName).performScrollTo().performClick()
            compose.onNodeWithText(f.label(R.string.record_upload_submit)).performClick()
            compose.waitUntil(10_000) { f.navigated == recordId }
            assertEquals(1, f.uploads.get()); assertTrue(f.declarations.single().contains("\"organization_id\":\"$organizationId\""))
            assertTrue(f.declarations.single().contains("\"candidate_user_ids\":[\"$memberId\"]"))
            assertFalse(f.declarations.single().contains("\"candidate_user_ids\":[\"$ownerId\"]"))
        }
    }
    private fun decision(action: String, resource: Int) {
        val f = Fixture()
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides f) { WeMeetTheme { RecordingUploadStatus(f.repository, ownerId, recordId) } } }
        waitForLabel(f, R.string.import_preflight_stopped)
        assertTrue(f.decisions.isEmpty()); compose.onNodeWithText(f.label(R.string.record_upload_retry)).assertDoesNotExist()
        compose.onNodeWithText(f.label(resource)).performClick()
        waitForLabel(f, R.string.record_upload_queued)
        assertEquals(action, f.decisions.single()["action"]); assertEquals(0, f.uploads.get()); assertEquals(0, f.puts.get())
        if (action == "continue_without_identity") compose.onNodeWithText(f.label(R.string.import_preflight_pending)).assertDoesNotExist()
        else compose.onNodeWithText(f.label(R.string.import_preflight_pending)).assertExists()
    }
    @Test fun continuingTranscriptionRequiresAnExplicitDecisionAndDoesNotReupload() = decision("continue_without_identity", R.string.import_preflight_continue)
    @Test fun retryingIdentityPreflightRequiresAnExplicitDecisionAndDoesNotRetryAsr() = decision("retry_identity", R.string.import_preflight_retry)

    @Test fun sameAccountReloginRemovesOldChooserWithoutUploadingOrNavigating() {
        val f = Fixture(); withImport(f) {
            enable(f); compose.onNodeWithContentDescription(f.selfName).performScrollTo().performClick()
            f.session = "new-synthetic-login"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(f.label(R.string.record_upload_title)).fetchSemanticsNodes().isEmpty() }
            assertEquals(0, f.uploads.get()); assertNull(f.navigated)
            compose.onNodeWithContentDescription(f.selfName).assertDoesNotExist()
        }
    }
    @Test fun uploadedBytesCanStillBeConfirmedAfterIdentityCapabilityIsDisabled() {
        val f = Fixture(direct = true); withImport(f) {
            enable(f); compose.onNodeWithContentDescription(f.selfName).performScrollTo().performClick()
            compose.onNodeWithText(f.label(R.string.record_upload_submit)).performClick()
            waitForLabel(f, R.string.record_upload_unconfirmed)
            assertEquals(1, f.puts.get()); f.matching = false; f.resume()
            waitForLabel(f, R.string.import_identity_unavailable)
            compose.onNodeWithText(f.label(R.string.record_upload_submit)).assertIsEnabled().performClick()
            compose.waitUntil(10_000) { f.navigated == recordId }
            assertEquals(1, f.puts.get()); assertEquals(2, f.completions.get()); assertEquals(f.declarations[0], f.declarations[1])
        }
    }
    @Test fun chineseDarkLargeTextKeepsLongNamesAndTheSubmitButtonReachable() {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val f = Fixture(instrumentation.targetContext.createConfigurationContext(configuration))
        f.selfName = "SyntheticLongNameWithoutSpaces".repeat(5)
        withImport(f, large = true) {
            enable(f); compose.onNodeWithContentDescription(f.selfName).performScrollTo().assertIsDisplayed().performClick()
            val name = compose.onNodeWithText(f.selfName).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertFalse(layouts.single().hasVisualOverflow)
            val selectedLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(f.context.getString(R.string.import_identity_remove, f.selfName))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(selectedLayouts)) }
            assertFalse(selectedLayouts.single().hasVisualOverflow)
            val bounds = name.fetchSemanticsNode().boundsInRoot
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= dialog.left && bounds.right <= dialog.right)
            compose.onNodeWithText(f.label(R.string.record_upload_submit)).assertIsDisplayed().assertIsEnabled()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(f.context.cacheDir, "import-identity-large-dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); assertEquals(0, f.uploads.get())
        }
    }
}
