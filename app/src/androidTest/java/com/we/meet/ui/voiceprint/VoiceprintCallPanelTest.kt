package com.we.meet.ui.voiceprint

import android.content.Context
import android.content.res.Configuration
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.dto.*
import com.we.meet.data.voiceprint.VoiceprintInputChanges
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose panel/controller; synthetic metadata only, no media or production account. */
@RunWith(AndroidJUnit4::class)
class VoiceprintCallPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var resources: Context? = null
    private fun text(id: Int, vararg args: Any) = (resources ?: context).getString(id, *args)
    private class Operations : VoiceprintCallOperations {
        var valid = true
        var readCount = 0
        var disabledScope: String? = null
        var settingsScope: Pair<String?, String?>? = null
        val writes = mutableListOf<Triple<Boolean, Boolean, String>>()
        var value = VoiceprintCallConnectionDto("RM_synthetic", "22222222-2222-4222-8222-222222222222", "Synthetic organization",
            "2099-10-10T00:00:03Z", VoiceprintCallLimitsDto(3000, 60000, 120000, 86400),
            VoiceprintCallPermissionDto(true, 7, true, true), VoiceprintCallControlDto("44444444-4444-4444-8444-444444444444", "PA_synthetic", 3,
                true, false, "", "paused", "", VoiceprintCallRuntimeDto("stopped", "paused", null)))
        override fun allowed() = valid
        override suspend fun capability() = Result.success(true)
        override suspend fun read(): Result<VoiceprintCallConnectionDto> { readCount++; return Result.success(value) }
        override suspend fun declare(snapshot: VoiceprintCallConnectionDto, paused: Boolean, shared: Boolean, device: String): Result<VoiceprintCallControlDto> {
            writes += Triple(paused, shared, device)
            val state = if (paused) "paused" else if (shared) "shared_microphone" else if (device.isEmpty()) "device_required" else "ready"
            value = value.copy(control = value.control.copy(revision = value.control.revision + 1, paused = paused, sharedMicrophone = shared,
                deviceGroup = device, state = state, runtime = VoiceprintCallRuntimeDto(if (state == "ready") "waiting" else "stopped", state, null)))
            return Result.success(value.control)
        }
        override suspend fun disableAccumulation(snapshot: VoiceprintCallConnectionDto): Result<VoiceprintSettingsDto> {
            disabledScope = snapshot.organizationId; value = value.copy(permission = value.permission.copy(allowAccumulation = false))
            return Result.success(VoiceprintSettingsDto(snapshot.organizationId, true, 8, 1, true, false, false, emptyList()))
        }
        fun sampling() {
            value = value.copy(control = value.control.copy(paused = false, deviceGroup = "headset", state = "ready",
                runtime = VoiceprintCallRuntimeDto("sampling", "", "2099-10-10T00:00:00Z")))
        }
    }
    private var time = 0L
    private lateinit var controller: VoiceprintCallController
    private lateinit var scope: CoroutineScope
    @org.junit.After fun cleanup() { if (::controller.isInitialized) compose.runOnUiThread { controller.close(); scope.cancel() } }
    private fun show(ops: Operations, microphone: Boolean = true, compact: Boolean = false, chinese: Boolean = false) {
        val config = Configuration(context.resources.configuration).apply { setLocale(if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH) }
        resources = context.createConfigurationContext(config)
        compose.runOnUiThread {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            controller = VoiceprintCallController(ops, scope) { time }
            controller.start(); controller.inputObservationAvailable(true)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides requireNotNull(resources), LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, if (chinese) 1.5f else 1f)) {
                WeMeetTheme(darkTheme = chinese) {
                    VoiceprintCallWidget(controller, microphone, compact, onSettings = { id, name -> ops.settingsScope = id to name })
                }
            }
        }
        compose.waitForIdle()
    }
    private fun status(id: Int) = text(R.string.voiceprint_call_status, text(id))
    private fun open(id: Int = R.string.voiceprint_call_phase_stopped) { compose.onNodeWithText(status(id)).performClick(); compose.waitForIdle() }
    private fun click(id: Int) { compose.onNodeWithText(text(id)).performScrollTo().assertIsEnabled().performClick(); compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Semantics may settle before the native dialog's next drawn frame.
        Thread.sleep(250)
        val directory = File(context.getExternalFilesDir(null), "voiceprint-call").apply { mkdirs() }
        File(directory, name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun openingOnlyReadsAndDraftDeviceNeedsSaveThenSeparateAllow() {
        val ops = Operations(); show(ops); open()
        assertEquals(1, ops.readCount); assertTrue(ops.writes.isEmpty())
        click(R.string.voiceprint_device_headset); assertTrue(ops.writes.isEmpty())
        compose.onNodeWithText(text(R.string.voiceprint_call_resume)).assertIsNotEnabled()
        click(R.string.voiceprint_call_save_device); assertEquals(listOf(Triple(true, false, "headset")), ops.writes)
        click(R.string.voiceprint_call_resume); assertEquals(Triple(false, false, "headset"), ops.writes.last())
        click(R.string.voiceprint_call_pause); assertEquals(Triple(true, false, "headset"), ops.writes.last())
    }
    @Test fun nativeDeviceMetadataObserverDoesNotRequireOrGrantMicrophonePermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val inputs = VoiceprintInputChanges(context)
        try { assertEquals(0L, inputs.revision.value) } finally { inputs.close() }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
    }
    @Test fun sharedMicrophoneChangeAlwaysPausesAndBlocksAllow() {
        val ops = Operations(); ops.value = ops.value.copy(control = ops.value.control.copy(deviceGroup = "headset")); show(ops); open()
        compose.onNode(hasText(text(R.string.voiceprint_call_shared)) and isToggleable()).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(listOf(Triple(true, true, "headset")), ops.writes)
        compose.onNodeWithText(text(R.string.voiceprint_call_resume)).assertIsNotEnabled()
    }
    @Test fun mutedMicrophoneNeverClaimsSamplingOrAllowsThisCall() {
        val ops = Operations(); ops.sampling(); show(ops, microphone = false)
        compose.onNodeWithText(status(R.string.voiceprint_call_phase_sampling)).assertDoesNotExist()
        open(R.string.voiceprint_call_phase_muted); compose.onNodeWithText(text(R.string.voiceprint_call_resume)).assertIsNotEnabled()
        assertTrue(ops.writes.isEmpty())
    }
    @Test fun compactPipShowsStatusWithoutInteractiveDeclaration() {
        val ops = Operations(); ops.sampling(); show(ops, compact = true)
        compose.onNodeWithText(status(R.string.voiceprint_call_phase_sampling)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voiceprint_call_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.voiceprint_call_resume)).assertDoesNotExist(); assertTrue(ops.writes.isEmpty())
    }
    @Test fun expiredSamplingProofChangesCaptionAndStaleConnectionDisablesAllow() {
        val ops = Operations(); ops.sampling(); show(ops)
        compose.runOnUiThread { time = 2000; controller.tick() }
        compose.onNodeWithText(status(R.string.voiceprint_call_phase_waiting)).assertIsDisplayed()
        compose.runOnUiThread { time = 15000; controller.tick() }; open(R.string.voiceprint_call_phase_unavailable)
        compose.onNodeWithText(text(R.string.voiceprint_call_resume)).assertIsNotEnabled(); assertTrue(ops.writes.isEmpty())
    }
    @Test fun accumulationOffAndSettingsUseOnlyTrustedCurrentOrganization() {
        val ops = Operations(); show(ops); open()
        val off = text(R.string.voiceprint_call_disable_accumulation, "Synthetic organization")
        compose.onNodeWithText(off).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(ops.value.organizationId, ops.disabledScope); compose.onNodeWithText(off).assertIsNotEnabled()
        click(R.string.voiceprint_call_settings); assertEquals(ops.value.organizationId to ops.value.organizationName, ops.settingsScope)
        assertTrue(ops.writes.isEmpty())
    }
    @Test fun loginChangeHidesTheOpenPrivateDialog() {
        val ops = Operations(); show(ops); open()
        compose.runOnUiThread { ops.valid = false; controller.tick() }
        compose.onNodeWithText(text(R.string.voiceprint_call_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.voiceprint_call_scope, "Synthetic organization")).assertDoesNotExist()
        assertTrue(ops.writes.isEmpty())
    }
    @Test fun returningFromBackgroundDoesNotReopenThePrivateDialogOrAllowSampling() {
        val ops = Operations(); show(ops); open()
        compose.runOnUiThread { scope.launch { controller.background() } }; compose.waitForIdle()
        compose.onNodeWithText(text(R.string.voiceprint_call_title)).assertDoesNotExist()
        compose.runOnUiThread { controller.start(); controller.inputObservationAvailable(true) }; compose.waitForIdle()
        compose.onNodeWithText(status(R.string.voiceprint_call_phase_stopped)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voiceprint_call_title)).assertDoesNotExist()
        assertEquals(listOf(Triple(true, true, "")), ops.writes)
    }
    @Test fun chineseDarkLargeTextKeepsCloseVisibleWhileBodyScrolls() {
        val ops = Operations(); show(ops, chinese = true); open()
        screenshot("call-panel-initial-zh-dark-large.png")
        try { compose.onNodeWithText(text(R.string.voiceprint_call_close)).assertIsDisplayed() }
        catch (error: AssertionError) { throw AssertionError(compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { compose.onAllNodes(isRoot())[it].printToString() }, error) }
        compose.onNodeWithText(text(R.string.voiceprint_call_resume)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voiceprint_call_close)).assertIsDisplayed()
        screenshot("call-panel-zh-dark-large.png")
        compose.onNodeWithText(text(R.string.voiceprint_call_close)).performClick()
        compose.onNodeWithText(text(R.string.voiceprint_call_title)).assertDoesNotExist(); assertTrue(ops.writes.isEmpty())
    }
}
