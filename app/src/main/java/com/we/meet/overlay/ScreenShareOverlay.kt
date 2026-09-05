package com.we.meet.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.we.meet.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "ScreenShareOverlay"

/**
 * Global system-overlay bubble that lets the user stop screen-share from the
 * desktop (or any other app) while we're backgrounded.
 *
 * Visibility is a function of two bits: we're screen-sharing AND the
 * Activity is not in the foreground. Callers update each bit independently
 * ([setSharing] from the share state flow, [setForeground] from the
 * Activity lifecycle) and the controller handles the debounce.
 *
 * Taps emit to [stopRequests] — [com.we.meet.ui.room.RoomScreen] collects
 * that and fans it into the existing `stopScreenShare` path, keeping this
 * module free of any ViewModel / LiveKit knowledge.
 *
 * Requires SYSTEM_ALERT_WINDOW (granted by the user via Settings on API 23+).
 * We silently no-op when the permission is missing — the share still works,
 * the user just loses the desktop affordance and can stop from the system
 * notification or by returning to the app.
 */
class ScreenShareOverlay(context: Context) {

    @Volatile private var sharing: Boolean = false
    @Volatile private var activityForeground: Boolean = true

    private var view: View? = null
    private val appContext: Context = context.applicationContext

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun setSharing(active: Boolean) {
        sharing = active
        refresh()
    }

    fun setForeground(active: Boolean) {
        activityForeground = active
        refresh()
    }

    /** True when the host OS lets us draw overlays right now. */
    fun canDrawOverlays(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    private fun refresh() {
        val shouldShow = sharing && !activityForeground
        if (shouldShow) show() else hide()
    }

    private fun show() {
        if (view != null) return
        if (!canDrawOverlays(appContext)) {
            Log.i(TAG, "show: SYSTEM_ALERT_WINDOW not granted, skipping bubble")
            return
        }

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = appContext.resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val iconPx = (28 * density).toInt()

        val bubble = FrameLayout(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            elevation = 8 * density
            // 50% overall translucency so the bubble doesn't occlude the
            // content the user is actually sharing. View.alpha fades the
            // whole subtree (background + icon) uniformly — cheaper than
            // baking half-alpha into each drawable.
            alpha = 0.5f
            setOnClickListener {
                // Two things happen in parallel: ask the host to stop the
                // share (collected by RoomScreen → RoomViewModel), and bring
                // our Activity back to the foreground. Users tap this when
                // they're done sharing and want to return to the meeting —
                // leaving them stranded on the desktop would be rude.
                _stopRequests.tryEmit(Unit)
                bringAppToFront(appContext)
            }
        }
        bubble.addView(
            ImageView(appContext).apply {
                setImageResource(R.drawable.ic_overlay_stop_share)
                // Red icon on white bubble — keeps the "stop" semantic colour
                // while staying readable through the bubble-wide 50% alpha.
                setColorFilter(0xFFEE4444.toInt())
                layoutParams = FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER)
            },
        )

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Anchor near the top-right, below the status bar / notification
            // chip area so it doesn't cover system UI or the MediaProjection
            // cast indicator.
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }

        runCatching { wm.addView(bubble, params) }
            .onSuccess { view = bubble }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    private fun hide() {
        val v = view ?: return
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(v) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
            .also { view = null }
    }

    /**
     * Bring our own Activity to the front. We resolve the launcher intent
     * via [android.content.pm.PackageManager.getLaunchIntentForPackage] so
     * we don't have to name [com.we.meet.MainActivity] directly from this
     * module. [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT] brings the existing
     * instance back without restarting it, preserving the live meeting UI
     * and Compose state.
     */
    private fun bringAppToFront(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "bringAppToFront failed", it) }
    }
}
