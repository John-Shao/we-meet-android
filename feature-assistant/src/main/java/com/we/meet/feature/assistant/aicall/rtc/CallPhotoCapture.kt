package com.we.meet.feature.assistant.aicall.rtc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.we.meet.feature.assistant.aicall.model.PhotoCleanupException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Native JPEG still capture; no video frame conversion or model video track. */
internal class CallPhotoCapture(private val context: Context) {
    private var active: Shot? = null
    private var closed = false

    suspend fun capture(front: Boolean): ByteArray {
        check(!closed && active == null)
        val shot = Shot(front).also { active = it }
        try {
            val raw = withTimeout(15_000) { shot.capture() }
            currentCoroutineContext().ensureActive()
            check(!closed)
            return withContext(Dispatchers.Default) { CallPhotoImage.encode(raw) }
        } finally {
            try { withContext(NonCancellable) { withTimeout(5_000) { shot.release() } } }
            catch (error: Exception) { throw PhotoCleanupException(error) }
            finally { if (active === shot) active = null }
        }
    }

    fun close() { closed = true; active?.cancel() }

    private inner class Shot(private val front: Boolean) {
        private val thread = HandlerThread("CallStillPhoto").apply { start() }
        private val handler = Handler(thread.looper)
        private val stopping = AtomicBoolean(false)
        private val opened = CompletableDeferred<CameraDevice>()
        private val configured = CompletableDeferred<CameraCaptureSession>()
        private val metered = CompletableDeferred<Unit>()
        private val image = CompletableDeferred<ByteArray>()
        private val released = CompletableDeferred<Unit>()
        private val releasing = AtomicBoolean(false)
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var preview: ImageReader? = null
        private var jpeg: ImageReader? = null
        private var opening = false
        private var meteringStarted = 0L
        private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        private var hdr = false

        private fun failed(error: Exception) {
            opened.completeExceptionally(error); configured.completeExceptionally(error)
            metered.completeExceptionally(error); image.completeExceptionally(error)
        }

        @SuppressLint("MissingPermission") // Authorized by the per-call controller.
        suspend fun capture(): ByteArray {
            val manager = context.getSystemService(CameraManager::class.java)
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: error("Requested camera unavailable")
            val characteristics = manager.getCameraCharacteristics(id)
            val map = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val sizes = checkNotNull(map.getOutputSizes(ImageFormat.JPEG)).toList()
            val size = sizes.filter { it.width.toLong() * it.height <= 12_500_000 && maxOf(it.width, it.height) <= 4096 }
                .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minBy { it.width.toLong() * it.height }
            val previewSizes = checkNotNull(map.getOutputSizes(ImageFormat.YUV_420_888)).toList()
            val small = previewSizes.filter { it.width <= 640 && it.height <= 480 }
                .maxByOrNull { it.width * it.height } ?: previewSizes.minBy { it.width * it.height }
            val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            afMode = when {
                CaptureRequest.CONTROL_AF_MODE_AUTO in modes -> CaptureRequest.CONTROL_AF_MODE_AUTO
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            hdr = CaptureRequest.CONTROL_SCENE_MODE_HDR in (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf())
            preview = ImageReader.newInstance(small.width, small.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    try { reader.acquireLatestImage()?.close() }
                    catch (error: Exception) { if (!stopping.get()) failed(error) }
                }, handler)
            }
            jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    try {
                        reader.acquireLatestImage()?.use { value ->
                            val plane = value.planes[0].buffer
                            val bytes = ByteArray(plane.remaining()).also { plane.get(it) }
                            if (!stopping.get()) image.complete(bytes)
                        }
                    } catch (error: Exception) { if (!stopping.get()) failed(error) }
                }, handler)
            }
            check(!stopping.get())
            opening = true
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        if (stopping.get()) camera.close() else opened.complete(camera)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        device = camera; failed(IllegalStateException("Photo camera disconnected")); camera.close()
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        device = camera; failed(IllegalStateException("Photo camera error $error")); camera.close()
                    }
                    override fun onClosed(camera: CameraDevice) { releaseReaders() }
                }, handler)
            } catch (error: Exception) { opening = false; throw error }
            val camera = opened.await()
            val surfaces = listOf(checkNotNull(preview).surface, checkNotNull(jpeg).surface)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value
                    if (stopping.get()) value.close() else configured.complete(value)
                }
                override fun onConfigureFailed(value: CameraCaptureSession) { failed(IllegalStateException("Cannot configure photo camera")) }
            }, handler)
            val captureSession = configured.await()
            val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(checkNotNull(preview).surface); controls(this)
            }
            meteringStarted = SystemClock.elapsedRealtime()
            captureSession.setRepeatingRequest(previewRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val age = SystemClock.elapsedRealtime() - meteringStarted
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
                    val exposure = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    val focus = afMode == CaptureRequest.CONTROL_AF_MODE_OFF || af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    val balance = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
                    if (age >= 500 && exposure && focus && balance) metered.complete(Unit)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    failed(IllegalStateException("Photo metering failed"))
                }
            }, handler)
            val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(checkNotNull(preview).surface); controls(this)
                if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
            captureSession.capture(trigger.build(), null, handler)
            val settled = withTimeoutOrNull(4_000) { metered.await(); true } ?: false
            check(!stopping.get())
            @Suppress("DEPRECATION")
            val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
            val degrees = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val orientation = (sensor + (if (front) degrees else -degrees) + 360) % 360
            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(checkNotNull(jpeg).surface); controls(this)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                if (hdr) {
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                }
            }
            Log.i("CallStillPhoto", "capture=native_jpeg size=$size metered=$settled hdrScene=$hdr front=$front orientation=$orientation")
            captureSession.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    failed(IllegalStateException("Still capture failed"))
                }
            }, handler)
            return image.await()
        }

        private fun controls(request: CaptureRequest.Builder) {
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        fun cancel() {
            stopping.set(true)
            failed(CancellationException("Photo owner ended"))
            handler.post {
                session?.close(); device?.close()
                if (!opening) releaseReaders()
            }
        }

        suspend fun release() {
            cancel()
            released.await()
            withContext(Dispatchers.IO) { thread.join(1000) }
        }

        // Late onOpened after cancellation still closes the device, then reaches
        // this callback. Never quit its looper while an open request is pending.
        private fun releaseReaders() {
            if (!releasing.compareAndSet(false, true)) return
            stopping.set(true)
            try { preview?.close(); jpeg?.close(); released.complete(Unit) }
            catch (error: Exception) { released.completeExceptionally(error) }
            finally { thread.quitSafely() }
        }
    }
}
