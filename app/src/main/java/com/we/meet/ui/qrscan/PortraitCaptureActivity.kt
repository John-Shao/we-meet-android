package com.we.meet.ui.qrscan

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Empty subclass of ZXing's [CaptureActivity] used purely to give it a
 * different `android:name` we can target in our own AndroidManifest. The
 * library ships its CaptureActivity locked to `sensorLandscape`; since the
 * app is otherwise portrait-only, we re-declare this subclass with
 * `sensorPortrait` so the scanner doesn't jarringly rotate the device.
 *
 * See `QrScanScreen.kt` — `setCaptureActivity(PortraitCaptureActivity::class.java)`.
 */
class PortraitCaptureActivity : CaptureActivity()
