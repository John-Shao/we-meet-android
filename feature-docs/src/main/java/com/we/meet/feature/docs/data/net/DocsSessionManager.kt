package com.we.meet.feature.docs.data.net

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.docs.DocsDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Native docs-session bootstrap (设计文档 §4.2).
 *
 * The docs REST API authenticates with a `docs_sessionid` **cookie** (+ CSRF
 * header on writes), not a Bearer token. This manager trades the app's own
 * login state for that session:
 *
 *  1. `POST {meet}/api/v1.0/docs/session/` (host-authenticated OkHttp) mints a
 *     one-time ticket URL;
 *  2. `GET {docs}/api/v1.0/session-from-ticket/?ticket=…` — the docs server
 *     sets `docs_sessionid` and 302s to the target page; OkHttp follows the
 *     redirects and the [cookieJar] captures the cookie;
 *  3. the cookie values persist in [DocsSessionStore] (encrypted) and are
 *     re-seeded into the jar on process restart.
 *
 * Write requests additionally send `X-CSRFToken` mirroring the `csrftoken`
 * cookie — Django's CSRF check compares the two values, so the client only
 * needs cookie/header agreement and never a server-side round-trip.
 */
class DocsSessionManager(
    context: Context,
    private val deps: DocsDeps,
) {

    val store = DocsSessionStore(context)

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /** Host of the docs site — cookies only ever attach to it. */
    private val docsHost: String =
        deps.docsBaseUrl.toHttpUrl().host

    // ---- Cookie jar: host-keyed in-memory map, hydrated from / persisted to the store ----

    private val cookiesByHost = ConcurrentHashMap<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookiesByHost[url.host] = cookies
            for (cookie in cookies) {
                if (cookie.name == COOKIE_SESSION_ID) store.sessionId = cookie.value
                if (cookie.name == COOKIE_CSRF) store.csrfToken = cookie.value
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            cookiesByHost[url.host]?.let { return it }
            if (url.host != docsHost) return emptyList()
            // Process restart: re-seed the jar from the encrypted store.
            return buildList {
                store.sessionId?.let { value ->
                    add(
                        Cookie.Builder()
                            .name(COOKIE_SESSION_ID)
                            .value(value)
                            .hostOnlyDomain(url.host)
                            .path("/")
                            .build(),
                    )
                }
                store.csrfToken?.let { value ->
                    add(
                        Cookie.Builder()
                            .name(COOKIE_CSRF)
                            .value(value)
                            .hostOnlyDomain(url.host)
                            .path("/")
                            .build(),
                    )
                }
            }.also { if (it.isNotEmpty()) cookiesByHost[url.host] = it }
        }
    }

    /** Mirrors the `csrftoken` cookie into `X-CSRFToken` for unsafe methods. */
    private val csrfInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.method in SAFE_METHODS) {
            chain.proceed(request)
        } else {
            val token = store.csrfToken
            if (token == null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header(HEADER_CSRF, token).build())
            }
        }
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .addInterceptor(csrfInterceptor)
        .apply { if (com.we.meet.feature.docs.BuildConfig.DEBUG) addInterceptor(docsLogging()) }
        .build()

    private fun docsLogging(): Interceptor =
        HttpLoggingInterceptor { msg -> Log.d("WeMeetHttp", msg) }
            .apply { level = HttpLoggingInterceptor.Level.BASIC }

    private val docsRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(deps.docsBaseUrl.trimEnd('/') + "/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val docsApi: DocsApi = docsRetrofit.create(DocsApi::class.java)

    private val ticketRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(deps.baseUrl.trimEnd('/') + "/")
        .client(deps.authedOkHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val ticketApi: DocsTicketApi = ticketRetrofit.create(DocsTicketApi::class.java)

    private val bootstrapMutex = Mutex()

    val hasSession: Boolean
        get() = !store.sessionId.isNullOrBlank()

    /**
     * Ensures a docs session exists (bootstrap if missing). Cheap no-op when a
     * session cookie is already present — repositories call it before each
     * request; the 401 path then retries once with a fresh bootstrap.
     */
    suspend fun ensureSession() {
        if (hasSession) return
        bootstrapMutex.withLock {
            if (!hasSession) bootstrapLocked()
        }
    }

    /** Force a fresh session (used by the 401 retry path and logout). */
    suspend fun bootstrap() = bootstrapMutex.withLock { bootstrapLocked() }

    /** Drops the stored session (401 recovery + app logout). */
    suspend fun invalidate() {
        store.clear()
        cookiesByHost.remove(docsHost)
    }

    private suspend fun bootstrapLocked() = withContext(Dispatchers.IO) {
        val url = ticketApi
            .createSession(DocsTicketRequest(next = "/"))
            .url
            ?: throw DocsSessionException("docs session ticket unavailable (docs not configured)")
        val request = Request.Builder().url(url).build()
        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DocsSessionException("docs session bootstrap failed: ${response.code}")
            }
        }
        ensureCsrfToken()
    }

    /**
     * Guarantees a `csrftoken` cookie exists. Django's CSRF check only requires
     * cookie/header agreement, so when the bootstrap chain didn't hand us one,
     * a locally generated 32-char token is valid for every subsequent write.
     */
    private fun ensureCsrfToken() {
        if (!store.csrfToken.isNullOrBlank()) return
        val token = buildString {
            val chars = "0123456789abcdef"
            repeat(32) { append(chars.random()) }
        }
        store.csrfToken = token
        cookiesByHost[docsHost] = buildList {
            store.sessionId?.let { value ->
                add(Cookie.Builder().name(COOKIE_SESSION_ID).value(value).hostOnlyDomain(docsHost).path("/").build())
            }
            add(Cookie.Builder().name(COOKIE_CSRF).value(token).hostOnlyDomain(docsHost).path("/").build())
        }
    }

    private companion object {
        const val COOKIE_SESSION_ID = "docs_sessionid"
        const val COOKIE_CSRF = "csrftoken"
        const val HEADER_CSRF = "X-CSRFToken"
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
    }
}

/** Docs session bootstrap failed — caller maps this to an error state. */
class DocsSessionException(message: String) : Exception(message)
