package com.we.meet.feature.assistant.aicall.vm

import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Per-call owner; permission and hardware completion are independently awaited. */
internal class CameraActionController(
    private val current: () -> Boolean,
    private val enabled: () -> Boolean?,
    private val available: () -> Boolean,
    private val visible: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val permissionRequested: (CameraPermissionRequest?) -> Unit,
    private val foregroundCamera: suspend (Boolean) -> Unit,
    private val mediaCamera: suspend (Boolean) -> Unit,
    private val applied: (Boolean) -> Unit,
    private val pending: (Boolean) -> Unit,
    private val unsafe: () -> Unit,
    private val result: (String, Boolean?, Boolean) -> CameraActionResult,
) {
    private enum class Permission { Granted, Denied, Cancelled }
    private class ForegroundRequired : IllegalStateException()
    private val serial = Mutex()
    private var permission: Pair<String, CompletableDeferred<Permission>>? = null
    private var closed = false
    private fun active() = !closed && current()

    fun permissionResult(id: String, granted: Boolean) {
        permission?.takeIf { it.first == id }?.second?.complete(if (granted) Permission.Granted else Permission.Denied)
    }

    private suspend fun authorize(): String? {
        if (permissionGranted()) return null
        val request = CameraPermissionRequest(UUID.randomUUID().toString())
        val deferred = CompletableDeferred<Permission>()
        permission = request.id to deferred
        val decision = try {
            permissionRequested(request)
            withTimeoutOrNull(60_000) { deferred.await() }
        } finally { permission = null; permissionRequested(null) }
        when (decision) {
            null -> return "timeout"
            Permission.Denied -> return "permission_denied"
            Permission.Cancelled -> return "cancelled"
            Permission.Granted -> Unit
        }
        withTimeoutOrNull(1000) { while (active() && !visible()) delay(20) }
        return when {
            !active() -> "cancelled"
            !visible() -> "foreground_required"
            !permissionGranted() -> "permission_denied"
            else -> null
        }
    }

    /** Serialize photos with mode changes, without applying a Voice/Video transition. */
    suspend fun takePhoto(capture: suspend () -> ByteArray, analyze: suspend (ByteArray) -> String): CameraActionResult = serial.withLock {
        if (!active()) return@withLock result("cancelled", enabled(), false)
        if (!visible()) return@withLock result("foreground_required", enabled(), false)
        val video = enabled() == true
        var foregroundUpgraded = false
        pending(true)
        try {
            authorize()?.let { return@withLock result(it, enabled(), false) }
            if (!video) withTimeout(10_000) { foregroundUpgraded = true; foregroundCamera(true) }
            if (!active()) return@withLock result("cancelled", enabled(), false)
            if (!visible()) return@withLock result("foreground_required", enabled(), false)
            val image = withTimeout(15_000) { capture() }
            if (!active()) return@withLock result("cancelled", enabled(), false)
            if (foregroundUpgraded) {
                withTimeout(10_000) { foregroundCamera(false) }
                foregroundUpgraded = false
            }
            val answer = withTimeout(35_000) { analyze(image) }
            if (!active()) return@withLock result("cancelled", enabled(), false)
            check(answer.isNotBlank())
            result("photo_answer", enabled(), false).copy(message = answer)
        } catch (error: Exception) {
            if (error is PhotoCleanupException) unsafe()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            result(if (error is TimeoutCancellationException) "timeout" else "photo_failed", enabled(), false)
        } finally {
            if (foregroundUpgraded && active()) withContext(NonCancellable) {
                if (runCatching { withTimeout(10_000) { foregroundCamera(false) } }.isFailure) unsafe()
            }
            if (active()) pending(false)
        }
    }

    suspend fun query(): CameraActionResult = serial.withLock {
        result(if (!active()) "cancelled" else when (enabled()) { true -> "already_enabled"; false -> "already_disabled"; null -> "device_error" }, enabled(), false)
    }

    suspend fun flip(operation: suspend () -> Boolean): Boolean = serial.withLock {
        check(active() && enabled() == true)
        pending(true)
        try { withTimeout(10_000) { operation() } } finally { if (active()) pending(false) }
    }

    suspend fun requestCameraEnabled(target: Boolean, source: CameraActionSource): CameraActionResult {
        // Explicit OFF supersedes a pending authorization, even while the mutex is held.
        if (!target) permission?.second?.complete(Permission.Cancelled)
        return serial.withLock {
            if (!active()) return@withLock result("cancelled", enabled(), false)
            if (enabled() == target) return@withLock result(if (target) "already_enabled" else "already_disabled", target, false)
            if (target && !available()) return@withLock result("video_unavailable", enabled(), false)
            if (target && !visible()) return@withLock result("foreground_required", enabled(), false)
            pending(true)
            try {
                if (target && !permissionGranted()) {
                    val code = authorize()
                    if (code != null) return@withLock result(code, enabled(), false)
                }
                try {
                    withTimeout(10_000) {
                        if (target) {
                            foregroundCamera(true)
                            // FGS acknowledgement is asynchronous. The page may
                            // have gone to the background while it was awaited.
                            if (!active()) throw CancellationException("Camera owner ended")
                            if (!visible()) throw ForegroundRequired()
                        }
                        mediaCamera(target)
                        check(active())
                        if (!target) foregroundCamera(false)
                    }
                    applied(target)
                    result(if (target) "enabled" else "disabled", target, true)
                } catch (error: Exception) {
                    // An interrupted/failed OPEN must not leave capture or sending alive.
                    // If OFF cannot be confirmed, close the entire owner immediately.
                    val safe = withContext(NonCancellable) {
                        runCatching {
                            withTimeout(10_000) { mediaCamera(false); if (active()) foregroundCamera(false) }
                        }.isSuccess
                    }
                    if (!safe) unsafe() else if (active()) applied(false)
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    result(when (error) {
                        is TimeoutCancellationException -> "timeout"
                        is ForegroundRequired -> "foreground_required"
                        else -> "device_error"
                    }, if (safe) false else null, false)
                }
            } finally {
                if (active()) pending(false)
            }
        }
    }

    fun close() {
        closed = true
        permission?.second?.complete(Permission.Cancelled)
        permission = null
        permissionRequested(null)
    }
}
