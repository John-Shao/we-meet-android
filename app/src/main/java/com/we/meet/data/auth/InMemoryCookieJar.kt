package com.we.meet.data.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple per-host in-memory cookie store. Backs the lobby flow:
 * `request-entry/` returns a Set-Cookie that the server uses on
 * subsequent polls to recognize the same waiting participant. Without
 * a CookieJar OkHttp drops Set-Cookie on the floor and every poll
 * spawns a fresh participant in the host's waiting list.
 *
 * Not persisted across process restarts — lobby participation is
 * always per-session so this is fine.
 */
class InMemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val perHost = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { perHost[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val perHost = store[url.host] ?: return emptyList()
        // Drop any expired cookies on the way out so the store doesn't
        // grow indefinitely across long-lived processes.
        val now = System.currentTimeMillis()
        val live = mutableListOf<Cookie>()
        perHost.entries.removeIf { (_, c) ->
            val expired = c.expiresAt in 1..now
            if (!expired) live += c
            expired
        }
        return live
    }
}
