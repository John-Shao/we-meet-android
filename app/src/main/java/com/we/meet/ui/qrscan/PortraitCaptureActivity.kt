package com.we.meet.ui.qrscan

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.journeyapps.barcodescanner.CaptureActivity
import com.we.meet.R

/**
 * ZXing's [CaptureActivity] subclassed for two reasons:
 *
 *  1. **Portrait lock.** The library ships its CaptureActivity locked to
 *     `sensorLandscape`; we re-declare this subclass with `sensorPortrait`
 *     in the manifest so the scanner doesn't rotate the device.
 *
 *  2. **Close button overlay.** The default scanner UI has no on-screen
 *     close affordance — users on gesture-nav phones (or those who just
 *     don't think to swipe back) have no obvious way out. We overlay a
 *     small circular X button in the top-left after super.onCreate
 *     finishes inflating the scanner layout. Tapping it `finish()`es the
 *     Activity, which mirrors a system back press: ZXing returns null
 *     contents and our `QrScanScreen` routes back to Home as Cancelled.
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addCloseButtonOverlay()
    }

    private fun addCloseButtonOverlay() {
        val density = resources.displayMetrics.density
        val size = (44 * density).toInt()
        val sideMargin = (12 * density).toInt()
        // Fallback top offset until the inset listener fires (e.g. on devices
        // that haven't dispatched insets yet). Matches typical status bar height.
        val initialTopMargin = (28 * density).toInt()

        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.bg_scanner_back_button)
            setColorFilter(Color.WHITE)
            contentDescription = getString(R.string.cancel)
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { finish() }
        }

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = initialTopMargin
            leftMargin = sideMargin
        }
        addContentView(closeButton, params)

        // Re-position once the system reports the real status-bar inset so
        // the button always sits just below it rather than overlapping.
        ViewCompat.setOnApplyWindowInsetsListener(closeButton) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = top + (4 * density).toInt()
            }
            insets
        }
    }
}
