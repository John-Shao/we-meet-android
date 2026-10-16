package com.we.meet.ui.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.text.Html
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.BuildConfig
import com.we.meet.data.api.AuthApi
import com.we.meet.data.auth.AuthInterceptor
import com.we.meet.data.auth.KeycloakOidc
import com.we.meet.data.auth.TokenRefreshAuthenticator
import com.we.meet.data.auth.TokenStore
import java.util.UUID
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Opt-in external identity test. Credentials come only from transient runner arguments. */
@RunWith(AndroidJUnit4::class)
class BilingualExternalAuthTest {
    @Test fun externalPkceLoginAndTokenStoreRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phone = args.getString("bilingual_demo_phone")
        val otp = args.getString("bilingual_demo_otp")
        assumeTrue("Requires an explicitly supplied demo account", phone != null && otp != null)
        val prefix = "bilingual-auth-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        val store = TokenStore(context)
        val cookies = mutableMapOf<String, Cookie>()
        val http = OkHttpClient.Builder().followRedirects(false).cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
                values.forEach { cookies["${it.domain}|${it.path}|${it.name}"] = it }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookies.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        }).build()
        val oidc = KeycloakOidc()
        val identity = BuildConfig.WE_MEET_KEYCLOAK_URL.trimEnd('/')
        val realm = "$identity/realms/meet/protocol/openid-connect"
        var authorized: OkHttpClient? = null
        var signedOut = false
        try {
            val verifier = oidc.newVerifier()
            val state = oidc.newState()
            val login = oidc.authorizeUrl(state, oidc.challengeS256(verifier))
            val html = http.newCall(Request.Builder().url(login).build()).execute().use {
                assertEquals(200, it.code)
                requireNotNull(it.body).string()
            }
            val form = requireNotNull(Regex("<form\\b[^>]*\\bid=\"wm-login-form\"[^>]*>").find(html)).value
            fun attribute(name: String): String = Html.fromHtml(
                requireNotNull(Regex("\\b$name=\"([^\"]+)\"").find(form)).groupValues[1],
                Html.FROM_HTML_MODE_LEGACY,
            ).toString()
            val action = attribute("action").toHttpUrl()
            val send = attribute("data-send").toHttpUrl()
            assertTrue(action.isHttps && action.host == identity.toHttpUrl().host)
            assertTrue(send.isHttps && send.host == BuildConfig.WE_MEET_BASE_URL.toHttpUrl().host)
            http.newCall(Request.Builder().url(send)
                .post(FormBody.Builder().add("phone", phone!!).build()).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(JSONObject(requireNotNull(it.body).string()).getBoolean("success"))
            }
            val redirect = http.newCall(Request.Builder().url(action)
                .post(FormBody.Builder().add("phone", phone!!).add("otp", otp!!).build()).build())
                .execute().use {
                    assertTrue("Identity form did not redirect", it.code in listOf(302, 303))
                    Uri.parse(requireNotNull(it.header("Location")))
                }
            assertTrue(redirect.toString().startsWith(KeycloakOidc.REDIRECT_URI + "?"))
            assertTrue("OIDC state mismatch", redirect.getQueryParameter("state") == state)
            val tokens = oidc.exchangeCode(requireNotNull(redirect.getQueryParameter("code")), verifier)
            store.accessToken = tokens.accessToken
            store.refreshToken = tokens.refreshToken
            store.idToken = tokens.idToken
            store.authFlow = TokenStore.AUTH_FLOW_WEB
            assertTrue(TokenStore(context).accessToken == tokens.accessToken)
            val authApi = Retrofit.Builder().baseUrl(BuildConfig.WE_MEET_BASE_URL.trimEnd('/') + "/")
                .client(http).addConverterFactory(MoshiConverterFactory.create(
                    Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
                .build().create(AuthApi::class.java)
            val statuses = mutableListOf<Int>()
            authorized = http.newBuilder().addInterceptor(AuthInterceptor(store))
                .authenticator(TokenRefreshAuthenticator(store, authApi, oidc))
                .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { statuses.add(it.code) } }
                .build()
            fun userInfo(): String = authorized!!.newCall(Request.Builder().url("$realm/userinfo").build())
                .execute().use {
                    assertEquals(200, it.code)
                    JSONObject(requireNotNull(it.body).string()).getString("sub")
                }
            val subject = userInfo()
            // Trigger a real server 401 without waiting for access-token expiry.
            store.accessToken = "bilingual-expired-test-token"
            assertTrue("Refreshed identity changed", userInfo() == subject)
            assertEquals(listOf(200, 401, 200), statuses)
            assertTrue(store.isWebFlow() && !store.refreshToken.isNullOrBlank())
            assertTrue(store.accessToken != "bilingual-expired-test-token")
            assertTrue(TokenStore(context).accessToken == store.accessToken)
            assertTrue(TokenStore(context).refreshToken == store.refreshToken)
            http.newCall(Request.Builder().url("$realm/logout").post(FormBody.Builder()
                .add("client_id", BuildConfig.WE_MEET_OIDC_CLIENT_ID)
                .add("refresh_token", store.refreshToken!!).build()).build()).execute().use {
                assertEquals(204, it.code)
                signedOut = true
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("bilingual_auth_result", "pkce=true external_userinfo=true forced_401_refresh=true encrypted_persistence=true identity_preserved=true session_revoked=true")
            })
        } finally {
            if (!signedOut && store.refreshToken != null) runCatching {
                http.newCall(Request.Builder().url("$realm/logout").post(FormBody.Builder()
                    .add("client_id", BuildConfig.WE_MEET_OIDC_CLIENT_ID)
                    .add("refresh_token", store.refreshToken!!).build()).build()).execute().close()
            }
            store.clear()
            context.baseContext.deleteSharedPreferences(prefix + "jusi_meet_tokens")
            cookies.clear()
            authorized?.connectionPool?.evictAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
