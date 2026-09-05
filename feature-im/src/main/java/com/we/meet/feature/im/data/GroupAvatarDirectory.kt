package com.we.meet.feature.im.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide cache for private custom group-avatar URLs.
 *
 * The backend intentionally keeps avatar object keys out of jusi conversation
 * metadata, so clients resolve cids through an authenticated batch endpoint.
 * Missing entries are cached too: otherwise every recomposition would repeat
 * the request for groups that correctly use the generated member mosaic.
 */
internal class GroupAvatarDirectory(
    private val bridge: ImBridgeRepository,
    private val scope: CoroutineScope,
) {
    private data class Entry(val url: String?, val resolvedAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(cid: String): String? = cache[cid]?.url

    fun requestResolve(cids: Collection<String>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val wanted = cids.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { cid ->
                val entry = cache[cid]
                (force || entry == null || now - entry.resolvedAtMs > FRESH_MS) &&
                    inFlight.add(cid)
            }
            .take(200)
            .toList()
        if (wanted.isEmpty()) return

        scope.launch {
            runCatching { bridge.resolveGroupAvatars(wanted) }
                .onSuccess { resolved ->
                    val resolvedAt = System.currentTimeMillis()
                    wanted.forEach { cid ->
                        cache[cid] = Entry(resolved[cid]?.takeIf { it.isNotBlank() }, resolvedAt)
                    }
                    _version.value += 1
                }
            wanted.forEach(inFlight::remove)
        }
    }

    /** Apply the authoritative response from upload/remove without another GET. */
    fun update(cid: String, url: String?) {
        cache[cid] = Entry(url?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
        _version.value += 1
    }

    fun clear() {
        cache.clear()
        inFlight.clear()
        _version.value += 1
    }

    private companion object {
        const val FRESH_MS = 60_000L
    }
}
