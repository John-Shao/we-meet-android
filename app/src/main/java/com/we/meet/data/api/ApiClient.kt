package com.we.meet.data.api

import android.util.Log
import com.we.meet.BuildConfig
import com.we.meet.data.auth.AuthInterceptor
import com.we.meet.data.auth.InMemoryCookieJar
import com.we.meet.data.auth.KeycloakOidc
import com.we.meet.data.auth.SessionExpiredInterceptor
import com.we.meet.data.auth.TokenRefreshAuthenticator
import com.we.meet.data.auth.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Application-scoped Retrofit + OkHttp setup.  Built once in
 * [com.we.meet.WeMeetApp] and shared by all repositories — never
 * re-instantiate per ViewModel.
 */
class ApiClient(tokenStore: TokenStore) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Refresh-only OkHttpClient. Built BEFORE the main client so the main
    // client's TokenRefreshAuthenticator can route 401 refresh attempts
    // through this independent dispatcher.
    //
    // Crucially this client has NO AuthInterceptor (so the stale bearer that
    // caused the original 401 doesn't get re-attached to the refresh POST)
    // and NO authenticator (so a refresh-side 401 cannot recurse back into
    // refresh). Without this isolation the main client's authenticator
    // `runBlocking { refresh() }` enqueues onto the same dispatcher whose
    // workers are blocked waiting for it — TCP stays ESTABLISHED, queues
    // sit 0:0, and OkHttp's per-call timeouts never fire because the call
    // is still in the dispatcher queue.
    private val refreshOkHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply { if (BuildConfig.DEBUG) addInterceptor(debugLoggingInterceptor()) }
        .build()

    private val refreshRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl(BuildConfig.WE_MEET_BASE_URL))
        .client(refreshOkHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val refreshAuthApi: AuthApi = refreshRetrofit.create(AuthApi::class.java)

    // Keycloak OIDC client for the in-WebView PKCE login (p3-docs-app.md).
    // Owns its own plain OkHttp — safe to call from inside the authenticator.
    val keycloakOidc: KeycloakOidc = KeycloakOidc()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Lobby flow needs Set-Cookie persistence across request-entry
        // polls. Other endpoints either don't set cookies (mobile auth
        // returns Bearer in body) or are fine with cookies travelling
        // alongside Authorization — the server prefers the latter.
        .cookieJar(InMemoryCookieJar())
        .addInterceptor(AuthInterceptor(tokenStore))
        .addInterceptor(SessionExpiredInterceptor(tokenStore))
        // Authenticator runs INSIDE RetryAndFollowUp, between AuthInterceptor
        // (request-side) and SessionExpiredInterceptor (response-side). On a
        // 401, refresh happens here transparently; only if refresh fails
        // does the 401 propagate up to SessionExpiredInterceptor and the
        // existing "session expired" flow kicks in.
        // The refresh call goes through the standalone refreshAuthApi to
        // avoid self-dependency on this client's dispatcher.
        .authenticator(TokenRefreshAuthenticator(tokenStore, refreshAuthApi, keycloakOidc))
        .apply { if (BuildConfig.DEBUG) addInterceptor(debugLoggingInterceptor()) }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl(BuildConfig.WE_MEET_BASE_URL))
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val authApi: AuthApi = retrofit.create(AuthApi::class.java)
    val roomApi: RoomApi = retrofit.create(RoomApi::class.java)
    val userApi: UserApi = retrofit.create(UserApi::class.java)
    val qrLoginApi: QrLoginApi = retrofit.create(QrLoginApi::class.java)
    val imBridgeApi: ImBridgeApi = retrofit.create(ImBridgeApi::class.java)
    val calendarApi: CalendarApi = retrofit.create(CalendarApi::class.java)
    /** P9 实体会议室 —— 与上面 roomApi(LiveKit 视频房间)无关。 */
    val meetingRoomApi: MeetingRoomApi = retrofit.create(MeetingRoomApi::class.java)
    val approvalApi: ApprovalApi = retrofit.create(ApprovalApi::class.java)
    val pushApi: PushApi = retrofit.create(PushApi::class.java)
    val searchApi: SearchApi = retrofit.create(SearchApi::class.java)
    val docsApi: DocsApi = retrofit.create(DocsApi::class.java)
    val taskApi: TaskApi = retrofit.create(TaskApi::class.java)

    private fun normalizedBaseUrl(raw: String): String =
        if (raw.endsWith("/")) raw else "$raw/"

    // App-tagged logger so log lines appear under the app's PID with a tag
    // we control. Some OEM logcat pipelines (Honor, Huawei) drop the default
    // "okhttp.OkHttpClient" tag into an encrypted system bucket that
    // `adb logcat --pid=<app>` cannot decode; routing through Log.d keeps
    // the lines visible.
    private fun debugLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { msg -> Log.d("WeMeetHttp", msg) }
            .apply { level = HttpLoggingInterceptor.Level.HEADERS }
}
