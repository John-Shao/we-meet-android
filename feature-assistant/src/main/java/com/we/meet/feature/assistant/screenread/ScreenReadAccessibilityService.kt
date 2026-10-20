package com.we.meet.feature.assistant.screenread

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.we.meet.feature.assistant.aicall.rtc.CallPhotoImage

/** Captures the real display when MediaProjection cannot see the foreground app (e.g. Chrome). */
class ScreenReadAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureJpeg(): ByteArray? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    if (bitmap == null) {
                        continuation.resume(null)
                        return
                    }
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
                    continuation.resume(output.toByteArray())
                } catch (_: Exception) {
                    continuation.resume(null)
                } finally {
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                continuation.resume(null)
            }
        })
    }

    companion object {
        @Volatile
        private var instance: ScreenReadAccessibilityService? = null

        internal fun accessForCapture(): ScreenReadAccessibilityService? = instance
    }
}

/** User-enableable fallback; the accessibility service must be enabled once in system settings. */
object ScreenReadAccessibility {
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(context, ScreenReadAccessibilityService::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns a normalized JPEG ready for both the transcript and photo QA. */
    suspend fun capture(context: Context): ByteArray? {
        if (!isEnabled(context)) return null
        val service = ScreenReadAccessibilityService.accessForCapture() ?: return null
        val started = android.os.SystemClock.elapsedRealtime()
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) service.captureJpeg() else null
        val rawMs = android.os.SystemClock.elapsedRealtime() - started
        val normalized = raw?.let { withContext(Dispatchers.Default) { CallPhotoImage.encode(it) } }
        android.util.Log.i(
            "OmniScreenRead",
            "accessibility screenshotMs=$rawMs rawBytes=${raw?.size ?: -1} normalizedBytes=${normalized?.size ?: -1}",
        )
        return normalized
    }
}
