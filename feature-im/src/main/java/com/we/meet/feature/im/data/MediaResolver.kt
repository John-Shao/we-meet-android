package com.we.meet.feature.im.data

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * object_key → presigned GET URL cache over `im/images/resolve/`.
 *
 * Server URLs live ~1h; entries are treated fresh for 50min and lazily
 * re-resolved after that. Callers must NEVER persist the URL or use it as an
 * image cache key — the object_key is the stable identity.
 */
internal class MediaResolver(private val bridge: ImBridgeRepository) {

    private data class Entry(val url: String, val resolvedAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Resolve one key (batched callers should prefer [resolveAll]). */
    suspend fun resolve(objectKey: String): String? =
        resolveAll(listOf(objectKey))[objectKey]

    /** Resolve many keys at once; returns whatever the backend recognises. */
    suspend fun resolveAll(objectKeys: Collection<String>): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        val missing = objectKeys.filter { key ->
            val entry = cache[key]
            entry == null || now - entry.resolvedAtMs > FRESH_MS
        }
        if (missing.isNotEmpty()) {
            runCatching { bridge.resolveMedia(missing) }
                .onSuccess { resolved ->
                    resolved.forEach { (key, url) -> cache[key] = Entry(url, now) }
                }
        }
        return objectKeys.mapNotNull { key -> cache[key]?.let { key to it.url } }.toMap()
    }

    fun clear() = cache.clear()

    private companion object {
        const val FRESH_MS = 50L * 60L * 1000L
    }
}
