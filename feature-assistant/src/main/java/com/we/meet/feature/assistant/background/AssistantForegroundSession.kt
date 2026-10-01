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
}

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
    private var closed = false

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

    private fun request() {
        ContextCompat.startForegroundService(context, Intent(context, AssistantForegroundService::class.java)
            .putExtra(AssistantForegroundService.EXTRA_SESSION, id))
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
