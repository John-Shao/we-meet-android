package com.we.meet.ui.ai

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.data.*
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.ui.AssistantCallScreen
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real foreground screen + system permission + real AOQ camera, including release APKs. */
class OmniCameraPermissionLiveTest {
    @get:Rule val compose = createAndroidComposeRule<com.we.meet.MainActivity>()
    @Test fun grantingCameraPermissionExecutesOriginalTargetAndShowsPreview() = probe(true)
    @Test fun denyingCameraPermissionKeepsVoiceCall() = probe(false)

    private fun probe(grant: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val context = instrumentation.targetContext
        // Revoke CAMERA before starting instrumentation (revoking during it kills the app).
        assertNotEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val app = context.applicationContext as WeMeetApp
        val network = context.getSystemService(ConnectivityManager::class.java)
        network.bindProcessToNetwork(network.activeNetwork)
        val api = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        val repository = AiAgentRepository(api)
        try { repository.fetchConfig() } catch (error: retrofit2.HttpException) {
            if (error.code() != 401) throw error
            app.authRepository.sendOtp("13800000009").getOrThrow()
            app.authRepository.verifyOtp("13800000009", "123456").getOrThrow()
        }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val prefs = AiCallPreferences(context); val original = prefs.load()
        lateinit var vm: AiCallViewModel
        compose.runOnIdle {
            vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiCallViewModel(context, repository, prefs, history = null) as T
            })[AiCallViewModel::class.java]
            vm.selectTransport(AiCallTransport.AOQ)
        }
        val deps = object : AssistantDeps {
            override val baseUrl = app.baseUrl
            override val authedOkHttp = app.authedOkHttp
        }
        try {
            compose.runOnIdle { compose.activity.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    WeMeetTheme { AssistantCallScreen(deps, {}) }
                }
            } }
            withTimeout(20_000) { while (vm.state.value.agentConfig == null) delay(50) }
            compose.runOnIdle { vm.startCall() }
            withTimeout(45_000) { while (vm.state.value.status !is AiCallStatus.Active) {
                check(vm.state.value.status !is AiCallStatus.Failed); delay(50)
            } }
            val client = vm.rtcClient
            val operation = async(Dispatchers.Main) { vm.requestCameraEnabled(true, CameraActionSource.Voice) }
            compose.waitUntil(15_000) { vm.state.value.cameraPermissionRequest != null }
            compose.waitForIdle() // Advance Compose so the permission-launch effect actually runs.
            val id = if (grant) "permission_allow_foreground_only_button" else "permission_deny_button"
            withTimeout(15_000) {
                while (true) {
                    compose.mainClock.advanceTimeByFrame()
                    val node = find(instrumentation.uiAutomation.rootInActiveWindow, id)
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                    delay(100)
                }
            }
            compose.waitForIdle()
            val result = withTimeout(15_000) { operation.await() }
            assertEquals(if (grant) "enabled" else "permission_denied", result.code)
            assertEquals(grant, vm.state.value.isCameraEnabled)
            assertEquals(grant, client!!.cameraEnabled)
            assertSame(client, vm.rtcClient)
            assertTrue(vm.state.value.status is AiCallStatus.Active)
            if (grant) {
                compose.waitForIdle()
                delay(2000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("camera-preview.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                compose.runOnIdle { vm.flipCamera() }
                withTimeout(12_000) { while (vm.state.value.cameraPending) delay(50) }
                assertTrue(vm.state.value.cameraFront)
                withContext(Dispatchers.Main) {
                    assertTrue(vm.requestCameraEnabled(false, CameraActionSource.Button).success)
                    assertTrue(vm.requestCameraEnabled(true, CameraActionSource.Button).success)
                    assertTrue(vm.state.value.cameraFront) // Reopening preserves last lens.
                }
                compose.runOnIdle { compose.activity.moveTaskToBack(true) }
                delay(700)
                withContext(Dispatchers.Main) {
                    assertTrue(vm.requestCameraEnabled(false, CameraActionSource.Button).success)
                    assertEquals("foreground_required", vm.requestCameraEnabled(true, CameraActionSource.Voice).code)
                    assertEquals(false, client.cameraEnabled)
                }
            }
        } finally { compose.runOnIdle { vm.endCall(); owner.viewModelStore.clear(); prefs.save(original) } }
    }

    private fun find(node: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.endsWith(":id/$id") == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), id)?.let { return it }
        return null
    }
}
