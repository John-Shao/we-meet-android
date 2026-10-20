package com.we.meet.feature.assistant.aicall.model

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ScreenShareSize(val width: Int, val height: Int)

/**
 * MediaProjection mirrors the display into a fixed-size virtual display. Keep
 * the long edge at 1280 so text stays readable; screen sharing also uses a
 * higher upload floor than camera because static text needs several samples to
 * survive encoder/vision processing. Both edges are even because H.264
 * encoders reject odd dimensions.
 */
object ScreenShareSizing {
    const val MAX_LONG_EDGE = 1280

    /** Screen content changes slowly, but text needs a few samples per second. */
    const val CAPTURE_FPS = 10

    /** Never let a low camera upload setting make shared text unreadable. */
    const val MIN_MODEL_UPLOAD_FPS = 4

    fun modelUploadFps(configured: Int): Int = configured.coerceAtLeast(MIN_MODEL_UPLOAD_FPS)

    fun fit(
        width: Int,
        height: Int,
        maxLongEdge: Int = MAX_LONG_EDGE,
    ): ScreenShareSize {
        require(width > 0 && height > 0) { "Display size must be positive" }
        require(maxLongEdge >= 2) { "Long edge must be at least 2 px" }
        val scale = min(1f, maxLongEdge.toFloat() / max(width, height))
        return ScreenShareSize(even((width * scale).roundToInt()), even((height * scale).roundToInt()))
    }

    fun forDisplay(context: Context): ScreenShareSize {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            val fallback = context.resources.displayMetrics
            metrics.widthPixels = fallback.widthPixels
            metrics.heightPixels = fallback.heightPixels
        }
        return fit(metrics.widthPixels, metrics.heightPixels)
    }

    private fun even(value: Int): Int = max(2, value / 2 * 2)
}
