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
                    // Crop system bars/other windows out of the accessibility capture.
                    val source = bitmap
                    val active = windows.firstOrNull { it.isActive }?.let { info ->
                        Rect().also(info::getBoundsInScreen)
                    }
                    val cropped: Bitmap = active?.let { bounds ->
                        runCatching {
                            val left = bounds.left.coerceIn(0, source.width - 1)
                            val top = bounds.top.coerceIn(0, source.height - 1)
                            val right = bounds.right.coerceIn(left + 1, source.width)
                            val bottom = bounds.bottom.coerceIn(top + 1, source.height)
                            if (right - left >= 2 && bottom - top >= 2) {
                                android.util.Log.i("OmniScreenRead", "accessibility crop=${right - left}x${bottom - top}")
                                Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                            } else source
                        }.getOrDefault(source)
                    } ?: source
                    val trimmed = trimContent(cropped)
                    val output = ByteArrayOutputStream()
                    trimmed.compress(Bitmap.CompressFormat.JPEG, 100, output)
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

    /** Remove the system status bar and the unused white area below a short page. */
    private fun trimContent(source: Bitmap): Bitmap {
        val software = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return source
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        val top = statusBar.coerceIn(0, (software.height - 2).coerceAtLeast(0))
        if (software.height - top < 32) return source
        val width = software.width
        val height = software.height
        val pixels = IntArray(width * (height - top))
        software.getPixels(pixels, 0, width, 0, top, width, height - top)
        var lastContent = top
        for (y in height - 1 downTo top) {
            var content = false
            var x = 0
            while (x < width) {
                val color = pixels[(y - top) * width + x]
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF
                if (r < 245 || g < 245 || b < 245) {
                    content = true
                    break
                }
                x += 4
            }
            if (content) {
                lastContent = y
                break
            }
        }
        val bottom = (lastContent + 24).coerceAtMost(height)
        if (bottom - top < 40) return source
        android.util.Log.i(
            "OmniScreenRead",
            "accessibility trim=${width}x${bottom - top} from=${software.width}x$height",
        )
        return runCatching {
            Bitmap.createBitmap(software, 0, top, width, bottom - top)
        }.getOrDefault(source)
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
