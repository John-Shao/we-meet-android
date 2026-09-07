package com.we.meet.feature.docs.data.net

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.DocsDeps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Each account generation owns its transport, so old responses cannot repopulate credentials. */
class DocsSessionManager internal constructor(private val deps: DocsDeps, val store: DocsCredentials) {
    constructor(context: Context, deps: DocsDeps) : this(deps, DocsSessionStore(context))
    private val lock = Any()
    private val bootstrapMutex = Mutex()
    private val origin = deps.docsBaseUrl.toHttpUrl()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private var epoch = 0L
    private var owner = store.ownerKey
    private var transport: Transport? = null
    private val ticketApi = Retrofit.Builder().baseUrl(deps.baseUrl.trimEnd('/') + "/")
        .client(deps.authedOkHttp).addConverterFactory(MoshiConverterFactory.create(moshi))
        .build().create(DocsTicketApi::class.java)

    private inner class Transport(val generation: Long) {
        var cookies: List<Cookie> = buildList {
            store.sessionId?.let { add(cookie("docs_sessionid", it)) }
            store.csrfToken?.let { add(cookie("csrftoken", it)) }
        }
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
                syncAccountLocked()
                if (generation != epoch || !sameOrigin(url)) emptyList()
                else cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
            }
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) = synchronized(lock) {
                syncAccountLocked()
                if (generation == epoch && sameOrigin(url)) {
                    cookies = (cookies.filterNot { old -> received.any {
                        it.name == old.name && it.domain == old.domain && it.path == old.path
                    } } + received).filter { it.expiresAt > System.currentTimeMillis() }
                    received.filter { it.path == "/" }.forEach {
                        val value = it.value.takeIf { _ -> it.expiresAt > System.currentTimeMillis() }
                        when (it.name) {
                            "docs_sessionid" -> store.sessionId = value
                            "csrftoken" -> store.csrfToken = value
                        }
                    }
                }
            }
        }
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).cookieJar(jar)
            .addInterceptor { chain ->
                if (!isCurrent(generation)) throw IOException("docs account changed")
                val response = chain.proceed(chain.request())
                if (!isCurrent(generation)) {
                    response.close()
                    throw IOException("docs account changed")
                }
                response
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val token = synchronized(lock) {
                    syncAccountLocked()
                    if (generation != epoch) throw IOException("docs account changed")
                    store.csrfToken
                }
                val authenticated = if (sameOrigin(request.url) && request.method !in setOf("GET", "HEAD", "OPTIONS", "TRACE") && token != null)
                    request.newBuilder().header("X-CSRFToken", token)
                        .header("Origin", origin.resolve("/")!!.toString().removeSuffix("/"))
                        .header("Referer", origin.toString()).build()
                    else if (!sameOrigin(request.url)) request.newBuilder()
                        .removeHeader("X-CSRFToken").removeHeader("Origin").removeHeader("Referer").build()
                    else request
                chain.proceed(authenticated)
            }.build()
        val api: DocsApi = Retrofit.Builder().baseUrl(deps.docsBaseUrl.trimEnd('/') + "/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(DocsApi::class.java)
    }

    private fun cookie(name: String, value: String): Cookie = Cookie.Builder().name(name).value(value)
        .hostOnlyDomain(origin.host).path("/").apply { if (origin.isHttps) secure() }.build()
    private fun sameOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
    private fun accountKey() = deps.docsAccountKey?.let { "${deps.docsBaseUrl}|${deps.baseUrl}|$it" }
    private fun syncAccountLocked() {
        val current = accountKey()
        if (owner != current) {
            clearLocked()
            owner = current
            store.ownerKey = current
        }
    }
    private fun clearLocked() {
        epoch++
        transport?.client?.dispatcher?.cancelAll()
        transport = null
        store.clear()
    }
    val generation: Long get() = synchronized(lock) { syncAccountLocked(); epoch }
    fun isCurrent(expected: Long): Boolean = synchronized(lock) {
        syncAccountLocked()
        expected == epoch && owner != null
    }
    fun checkGeneration(expected: Long) {
        if (!isCurrent(expected)) throw CancellationException("docs account changed")
    }
    private fun transport(expected: Long): Transport = synchronized(lock) {
        checkGeneration(expected)
        transport ?: Transport(expected).also { transport = it }
    }
    val okHttp: OkHttpClient get() = transport(generation).client
    fun api(expected: Long): DocsApi = transport(expected).api
    val hasSession: Boolean get() = synchronized(lock) { syncAccountLocked(); !store.sessionId.isNullOrBlank() }

    suspend fun ensureSession(expected: Long = generation) {
        checkGeneration(expected)
        if (hasSession) return
        bootstrapMutex.withLock {
            checkGeneration(expected)
            if (!hasSession) bootstrapLocked(expected)
        }
    }
    suspend fun renewSession(expiredSessionId: String?, expected: Long = generation) = bootstrapMutex.withLock {
        synchronized(lock) {
            checkGeneration(expected)
            if (store.sessionId != expiredSessionId && hasSession) return@withLock
            store.sessionId = null
            store.csrfToken = null
            transport?.cookies = emptyList()
        }
        bootstrapLocked(expected)
    }
    suspend fun bootstrap() = bootstrapMutex.withLock { bootstrapLocked(generation) }

    /** Synchronous invalidation happens before navigation or token changes. */
    fun invalidate() = synchronized(lock) {
        clearLocked()
        owner = null
        store.ownerKey = null
    }

    private suspend fun bootstrapLocked(expected: Long) = withContext(Dispatchers.IO) {
        checkGeneration(expected)
        val url = ticketApi.createSession(DocsTicketRequest(next = "/")).url
            ?: throw DocsSessionException("docs session ticket unavailable")
        checkGeneration(expected)
        val request = Request.Builder().url(url).build()
        if (!sameOrigin(request.url)) throw DocsSessionException("unexpected docs ticket origin")
        transport(expected).client.newCall(request).execute().use { response ->
            checkGeneration(expected)
            if (!response.isSuccessful) throw DocsSessionException("docs bootstrap failed: ${response.code}")
        }
        synchronized(lock) {
            checkGeneration(expected)
            if (!hasSession) throw DocsSessionException("docs bootstrap did not establish a session")
            if (store.csrfToken.isNullOrBlank()) {
                val random = SecureRandom()
                val token = buildString { repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) } }
                store.csrfToken = token
                transport(expected).cookies = transport(expected).cookies.filterNot { it.name == "csrftoken" } + cookie("csrftoken", token)
            }
        }
    }
}

class DocsSessionException(message: String) : Exception(message)
