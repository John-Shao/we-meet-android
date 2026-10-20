package com.we.meet.feature.assistant.screenread

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        val active = windows.firstOrNull { it.isActive }?.let { info ->
            Rect().also(info::getBoundsInScreen)
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val colorSpace = result.colorSpace
                // Pixel scanning and JPEG compression must not block the main thread.
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        if (!continuation.isActive) return@launch
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        if (bitmap == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return@launch
                        }
                        val cropped: Bitmap = runCatching {
                            val bounds = ScreenSnapshotBounds.activeWindow(
                                bitmap.width,
                                bitmap.height,
                                active?.let {
                                    ScreenSnapshotBounds.Bounds(it.left, it.top, it.right, it.bottom)
                                },
                            )
                            if (bounds.isFullOf(bitmap.width, bitmap.height)) bitmap
                            else Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
                        }.getOrDefault(bitmap)
                        val trimmed = trimContent(cropped, cropStatusBar = active == null || active.top <= 0)
                        val output = ByteArrayOutputStream()
                        trimmed.compress(Bitmap.CompressFormat.JPEG, 100, output)
                        if (continuation.isActive) continuation.resume(output.toByteArray())
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    } finally {
                        buffer.close()
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    companion object {
        @Volatile
        private var instance: ScreenReadAccessibilityService? = null

        internal fun accessForCapture(): ScreenReadAccessibilityService? = instance
    }

    /** Remove the system status bar and the unused white area below a short page. */
    private fun trimContent(source: Bitmap, cropStatusBar: Boolean): Bitmap {
        val software = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return source
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        val width = software.width
        val row = IntArray(width)
        val bounds = ScreenSnapshotBounds.content(
            width = width,
            height = software.height,
            statusBarHeight = statusBar,
            cropStatusBar = cropStatusBar,
        ) { y ->
            software.getPixels(row, 0, width, 0, y, width, 1)
            row
        }
        if (bounds.isFullOf(software.width, software.height)) return source
        android.util.Log.i(
            "OmniScreenRead",
            "accessibility trim=${bounds.width}x${bounds.height} from=${software.width}x${software.height} statusCrop=$cropStatusBar",
        )
        return runCatching {
            Bitmap.createBitmap(software, bounds.left, bounds.top, bounds.width, bounds.height)
        }.getOrDefault(source)
    }
}

/** User-enableable fallback; the accessibility service must be enabled once in system settings. */
object ScreenReadAccessibility {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isEnabled(context: Context): Boolean {
        if (!isSupported()) return false
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
        val raw = if (isSupported()) service.captureJpeg() else null
        val rawMs = android.os.SystemClock.elapsedRealtime() - started
        val normalized = raw?.let { withContext(Dispatchers.Default) { CallPhotoImage.encode(it) } }
        android.util.Log.i(
            "OmniScreenRead",
            "accessibility screenshotMs=$rawMs rawBytes=${raw?.size ?: -1} normalizedBytes=${normalized?.size ?: -1}",
        )
        return normalized
    }
}
