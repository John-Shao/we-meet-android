package com.we.meet.feature.assistant.aicall.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import retrofit2.HttpException

@JsonClass(generateAdapter = true)
data class DirectAILeaseInfo(val id: String,
    @Json(name = "ttl_seconds") val ttlSeconds: Int,
    @Json(name = "heartbeat_seconds") val heartbeatSeconds: Int,
    val enforce: Boolean = false,
) { override fun toString() = "DirectAILeaseInfo(<private>)" }

@JsonClass(generateAdapter = true)
data class DirectAILeaseOperation(val operation: String)

/** Observes the app session, independently of vendor billing or token expiration. */
class DirectAILease(
    private val info: DirectAILeaseInfo,
    private val update: suspend (String, DirectAILeaseOperation) -> Unit,
    private val onLost: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var heartbeat: Job? = null

    init {
        require(UUID.fromString(info.id).toString() == info.id)
        require(info.heartbeatSeconds in 10..30 && info.ttlSeconds >= info.heartbeatSeconds * 4)
    }

    fun start() {
        check(heartbeat == null && !closed.get())
        heartbeat = scope.launch {
            var failures = 0
            while (isActive && !closed.get()) {
                try {
                    withTimeout(10_000) { update(info.id, DirectAILeaseOperation("heartbeat")) }
                    failures = 0
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    failures++
                    if ((error is HttpException && error.code() in setOf(401, 403, 404, 410)) || failures >= 3) {
                        // Lost observation does not claim that a still-working vendor connection ended.
                        if (info.enforce) {
                            try { if (!closed.get()) onLost() } finally { close() }
                        } else {
                            closed.set(true)
                            scope.cancel() // The server expires the declaration; audio remains independent.
                        }
                        break
                    }
                }
                delay(info.heartbeatSeconds * 1000L)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel()
        scope.launch {
            try { withTimeout(10_000) { update(info.id, DirectAILeaseOperation("close")) } }
            catch (_: Exception) { /* The server reclaims offline declarations after their lease expires. */ }
            finally { scope.cancel() }
        }
    }
}
