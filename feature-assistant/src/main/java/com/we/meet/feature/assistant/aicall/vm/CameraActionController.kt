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
                    val request = CameraPermissionRequest(UUID.randomUUID().toString())
                    val deferred = CompletableDeferred<Permission>()
                    permission = request.id to deferred
                    val decision = try {
                        permissionRequested(request)
                        withTimeoutOrNull(60_000) { deferred.await() }
                    } finally { permission = null; permissionRequested(null) }
                    val code = when (decision) {
                        null -> "timeout"
                        Permission.Denied -> "permission_denied"
                        Permission.Cancelled -> "cancelled"
                        Permission.Granted -> null
                    }
                    if (code != null) return@withLock result(code, enabled(), false)
                    // Permission callbacks may run just before Activity.onResume.
                    withTimeoutOrNull(1000) { while (active() && !visible()) delay(20) }
                    if (!active()) return@withLock result("cancelled", enabled(), false)
                    if (!visible()) return@withLock result("foreground_required", enabled(), false)
                    if (!permissionGranted()) return@withLock result("permission_denied", enabled(), false)
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
