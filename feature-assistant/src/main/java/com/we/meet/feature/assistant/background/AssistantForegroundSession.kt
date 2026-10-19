package com.we.meet.feature.assistant.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A foreground-service lease acquired before opening microphone/camera resources. */
interface AssistantSessionLease : Closeable {
    suspend fun camera(enabled: Boolean)
    suspend fun screenShare(enabled: Boolean) = Unit
    fun onScreenShareStop(action: () -> Unit) = Unit
    fun controls(state: AssistantControlState, input: () -> Unit, output: () -> Unit) = Unit
}

data class AssistantControlState(val ready: Boolean = false, val inputPaused: Boolean = false, val outputMuted: Boolean = false)

enum class AssistantSessionKind { CALL, TRANSLATION }

/** Main-thread ownership prevents a stale notification or cleanup from ending a newer session. */
class AssistantForegroundSession private constructor(
    private val context: Context,
    val kind: AssistantSessionKind,
    internal var cameraEnabled: Boolean,
    private val stopped: () -> Unit,
) : AssistantSessionLease {
    internal val id = UUID.randomUUID().toString()
    internal var confirmation = CompletableDeferred<Unit>()
    internal var attached = false
    internal var screenShareEnabled = false
        private set
    internal var pendingScreenShareUpgrade = false
    internal var foregroundStarted = false
    private var closed = false
    internal var controlState = AssistantControlState()
        private set
    private var inputAction: (() -> Unit)? = null
    private var outputAction: (() -> Unit)? = null
    private var screenShareStopAction: (() -> Unit)? = null

    override fun onScreenShareStop(action: () -> Unit) {
        if (closed || current !== this) return
        screenShareStopAction = action
    }

    internal fun screenShareStopRequested() {
        if (!closed && current === this && screenShareEnabled) screenShareStopAction?.invoke()
    }

    override fun controls(state: AssistantControlState, input: () -> Unit, output: () -> Unit) {
        if (closed || current !== this) return
        controlState = state
        inputAction = input
        outputAction = output
        AssistantForegroundService.refresh(this)
    }

    internal fun control(input: Boolean) {
        if (!closed && current === this && controlState.ready) {
            if (input) inputAction?.invoke() else outputAction?.invoke()
        }
    }

    override suspend fun camera(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        check(!closed && current === this@AssistantForegroundSession)
        if (cameraEnabled != enabled) {
            val previous = cameraEnabled
            cameraEnabled = enabled
            confirmation = CompletableDeferred()
            try {
                request()
                withTimeout(5000) { confirmation.await() }
            } catch (error: Throwable) {
                cameraEnabled = previous
                throw error
            }
        }
    }

    /**
     * Adds/removes the mediaProjection foreground-service type. Android 14
     * requires that type to be live before `getMediaProjection()`, so this
     * must complete before the transport starts its screen capturer.
     */
    override suspend fun screenShare(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        check(!closed && current === this@AssistantForegroundSession)
        if (screenShareEnabled != enabled) {
            val previous = screenShareEnabled
            screenShareEnabled = enabled
            pendingScreenShareUpgrade = enabled
            confirmation = CompletableDeferred()
            try {
                request()
                withTimeout(5000) { confirmation.await() }
            } catch (error: Throwable) {
                // A failed upgrade means sharing never became live. A failed
                // downgrade still leaves the capture stopped; keep the flag
                // false so the notification and camera checks stay truthful.
                screenShareEnabled = if (enabled) previous else false
                AssistantForegroundService.refresh(this@AssistantForegroundSession)
                throw error
            } finally {
                pendingScreenShareUpgrade = false
            }
        }
    }

    internal fun screenShareUpgradeFailed() {
        screenShareEnabled = false
        AssistantForegroundService.refresh(this)
    }

    private fun request() {
        val intent = Intent(context, AssistantForegroundService::class.java)
            .putExtra(AssistantForegroundService.EXTRA_SESSION, id)
        // An attached service is already foreground, so a plain (re)start is
        // allowed from the background; only the first start needs the
        // foreground-service entry point.
        if (attached) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
    }

    override fun close() {
        if (closed) return
        closed = true
        confirmation.cancel()
        if (current === this) {
            current = null
            // A queued startForegroundService must first be promoted by onCreate.
            // Stopping it before that can crash the process on newer Android.
            if (attached) context.stopService(Intent(context, AssistantForegroundService::class.java))
        }
    }

    internal fun interrupted() {
        if (closed) return
        close()
        stopped()
    }

    companion object {
        internal var current: AssistantForegroundSession? = null
            private set

        suspend fun start(context: Context, kind: AssistantSessionKind, camera: Boolean, stopped: () -> Unit): AssistantSessionLease =
            withContext(Dispatchers.Main.immediate) {
                check(current == null) { "An AI assistant session is already running" }
                val session = AssistantForegroundSession(context.applicationContext, kind, camera, stopped)
                current = session
                try {
                    session.request()
                    withTimeout(5000) { session.confirmation.await() }
                    session
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
    }
}
