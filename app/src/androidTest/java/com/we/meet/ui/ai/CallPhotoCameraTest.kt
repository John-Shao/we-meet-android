package com.we.meet.ui.ai

import android.Manifest
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.aicall.rtc.OmniAoqClient
import com.we.meet.feature.assistant.history.AssistantHistoryPhoto
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import kotlinx.coroutines.*
import livekit.org.webrtc.PeerConnectionFactory
import org.junit.Assert.*
import org.junit.Test

/** Camera2 and AOQ's photo-to-transcript callback on a physical device, without a paid allocation. */
class CallPhotoCameraTest {
    @Test fun actualCameraJpegIsTheSamePhotoInsertedIntoTheTranscript() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val rows = mutableListOf<AssistantHistoryRow>()
        ActivityScenario.launch(com.we.meet.MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            withContext(Dispatchers.Main) {
                val client = OmniAoqClient(context, {}, {}, onTranscript = { rows += it })
                try {
                    val jpeg = withTimeout(20_000) { client.capturePhoto() }
                    assertEquals(1, rows.size)
                    assertEquals("user", rows.single().role)
                    assertArrayEquals(jpeg, (rows.single().photo as AssistantHistoryPhoto.Memory).jpeg)
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    assertNotNull(bitmap); assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    bitmap.recycle()
                    assertEquals(false, client.cameraEnabled)
                    assertTrue(jpeg.size <= 512_000)
                } finally { client.close() }
            }
        }
    }
}
