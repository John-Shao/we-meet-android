package com.we.meet.feature.im.data

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * uid → display-identity cache over `im/users/resolve/`.
 *
 * Batch-resolves misses, dedupes in-flight uids, and bumps [version] on every
 * write so Compose recomposes rows that called [get] synchronously. Entries
 * expire after 1h (names rarely change; avatar staleness is tolerated because
 * Coil caches the bytes under a stable key anyway).
 */
internal class UserDirectory(
    private val bridge: ImBridgeRepository,
    private val scope: CoroutineScope,
) {
    private data class CachedProfile(val info: ImUserInfo, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedProfile>()
    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    private val _version = MutableStateFlow(0)

    /** Bumped after every successful resolve — collect to re-read [get] results. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Synchronous cache read; null when unknown (callers should [requestResolve]). */
    fun get(uid: String): ImUserInfo? = cache[uid]?.info

    /** Fire-and-forget resolve of any uncached/stale uids. Safe to spam from UI. */
    fun requestResolve(uids: Collection<String>) {
        val now = SystemClock.elapsedRealtime()
        val missing = uids.filter { uid ->
            val entry = cache[uid]
            entry == null || now - entry.fetchedAtMs > TTL_MS
        }
        if (missing.isEmpty()) return
        scope.launch {
            val toFetch = inFlightLock.withLock {
                val fresh = missing.filter { it !in inFlight }
                inFlight.addAll(fresh)
                fresh
            }
            if (toFetch.isEmpty()) return@launch
            try {
                resolveNow(toFetch)
            } finally {
                inFlightLock.withLock { inFlight.removeAll(toFetch.toSet()) }
            }
        }
    }

    /** Suspend resolve returning what's known afterwards (cache + fresh fetch). */
    suspend fun resolve(uids: Collection<String>): Map<String, ImUserInfo> {
        val now = SystemClock.elapsedRealtime()
        val missing = uids.filter { uid ->
            val entry = cache[uid]
            entry == null || now - entry.fetchedAtMs > TTL_MS
        }
        if (missing.isNotEmpty()) resolveNow(missing)
        return uids.mapNotNull { uid -> cache[uid]?.let { uid to it.info } }.toMap()
    }

    fun clear() {
        cache.clear()
        _version.value = 0
    }

    private suspend fun resolveNow(uids: List<String>) {
        runCatching { bridge.resolveUsers(uids) }
            .onSuccess { resolved ->
                if (resolved.isEmpty()) return@onSuccess
                val now = SystemClock.elapsedRealtime()
                resolved.forEach { (uid, info) -> cache[uid] = CachedProfile(info, now) }
                _version.value = _version.value + 1
            }
    }

    private companion object {
        const val TTL_MS = 60L * 60L * 1000L
    }
}
