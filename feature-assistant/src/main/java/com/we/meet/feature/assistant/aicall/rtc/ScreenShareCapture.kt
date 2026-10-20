package com.we.meet.feature.assistant.aicall.rtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SurfaceTextureHelper

/**
 * One screen-capture session backed by one MediaProjection consent token.
 *
 * [livekit.org.webrtc.ScreenCapturerAndroid] accepts the result Intent from
 * `MediaProjectionManager.createScreenCaptureIntent()` and owns the virtual
 * display. Android 14 allows one `getMediaProjection()` call per consent, so a
 * captured session is never restarted: toggling sharing off and on requires a
 * fresh consent Intent.
 *
 * The host call must already be running a `mediaProjection` foreground service
 * before [start] (API 34+), otherwise `getMediaProjection()` throws.
 */
internal class ScreenShareCapture(
    private val context: Context,
    private val eglContext: EglBase.Context,
    private val onSystemStop: () -> Unit,
    private val onContentResize: (Int, Int) -> Unit = { _, _ -> },
    private val onVisibilityChanged: (Boolean) -> Unit = {},
) {
    private enum class State { Idle, Starting, Active, Stopping }

    private var capturer: ScreenCapturerAndroid? = null
    private var texture: SurfaceTextureHelper? = null
    private var fps = 0

    @Volatile
    private var state = State.Idle

    @Volatile
    private var stopping = false

    @Volatile
    private var stoppedWhileStarting = false

    @Volatile
    private var pendingResize: Pair<Int, Int>? = null

    @Volatile
    private var pendingVisibility: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(resultData: Intent, observer: CapturerObserver, width: Int, height: Int, fps: Int) {
        check(state == State.Idle)
        state = State.Starting
        stopping = false
        stoppedWhileStarting = false
        pendingResize = null
        pendingVisibility = null
        this.fps = fps
        val helper = SurfaceTextureHelper.create("OmniScreenShare", eglContext)
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // Runs on the SurfaceTextureHelper thread. Do not touch the
                // capturer here: stopCapture() posts to this same thread.
                if (stopping) return
                when (state) {
                    State.Starting -> stoppedWhileStarting = true
                    State.Active -> {
                        state = State.Stopping
                        onSystemStop()
                    }
                    else -> Unit
                }
            }

            // Android 14 single-app sharing can change the captured content's
            // size and can hide it when the user switches away from that app.
            @SuppressLint("NewApi")
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (stopping) return
                when (state) {
                    State.Starting -> pendingResize = width to height
                    State.Active -> onContentResize(width, height)
                    else -> Unit
                }
            }

            @SuppressLint("NewApi")
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                if (stopping) return
                when (state) {
                    State.Starting -> pendingVisibility = isVisible
                    State.Active -> onVisibilityChanged(isVisible)
                    else -> Unit
                }
            }
        }
        val screen = ScreenCapturerAndroid(resultData, projectionCallback)
        capturer = screen
        texture = helper
        try {
            screen.initialize(helper, context, observer)
            screen.startCapture(width, height, fps)
        } catch (error: Exception) {
            state = State.Idle
            throw error
        }
        if (stoppedWhileStarting) {
            state = State.Stopping
            throw IllegalStateException("MediaProjection stopped during startup")
        }
        state = State.Active
        // startup callbacks can arrive before startCapture() returns; replay
        // them on the main thread after the caller has marked the session live.
        val resize = pendingResize
        val visibility = pendingVisibility
        pendingResize = null
        pendingVisibility = null
        if (resize != null || visibility != null) {
            mainHandler.post {
                if (state != State.Active || stopping) return@post
                resize?.let { onContentResize(it.first, it.second) }
                visibility?.let(onVisibilityChanged)
            }
        }
    }

    /** Reconfigure the virtual display after an Android 14 content resize. */
    fun changeFormat(width: Int, height: Int) {
        val screen = capturer ?: return
        if (state != State.Active || width <= 0 || height <= 0 || fps <= 0) return
        runCatching { screen.changeCaptureFormat(width, height, fps) }
    }

    /** Safe to call from the main thread, including after a system stop. */
    fun stop() {
        val screen = capturer
        state = State.Stopping
        stopping = true
        capturer = null
        mainHandler.removeCallbacksAndMessages(null)
        if (screen != null) {
            // stopCapture is synchronous and stops the projection, which may
            // synchronously invoke the callback above; stopping suppresses it.
            runCatching { screen.stopCapture() }
            runCatching { screen.dispose() }
        }
        val helper = texture
        texture = null
        runCatching { helper?.dispose() }
        state = State.Idle
    }
}
