package com.we.meet.ui.records

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.globalAskStream
import com.we.meet.data.auth.AuthInterceptor
import com.we.meet.data.auth.AuthSnapshot
import com.we.meet.data.auth.TokenStore
import com.we.meet.feature.im.ui.search.AskEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthAcceptHeaderTest {
    // Separate encrypted preferences: never replace the user's target-app session.
    private val testContext = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("auth-accept-test-" + name, mode)
    }
    private val store = TokenStore(testContext)
    private var captured: Request? = null
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).addInterceptor { chain ->
            captured = chain.request()
            val sse = chain.request().header("Accept") == "text/event-stream"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (sse || !chain.request().url.encodedPath.contains("ask-stream")) 200 else 406)
                .message("fixture")
                .body((if (sse) "data: {\"type\":\"meta\",\"citations\":[]}\n\ndata: {\"type\":\"done\",\"citations_used\":[]}\n\n" else "{}")
                    .toResponseBody((if (sse) "text/event-stream" else "application/json").toMediaType()))
                .build()
        }.build()
    }
    @Before fun login() { store.clear(); store.accessToken = "fixture-access" }
    @After fun cleanup() { store.clear() }

    @Test fun defaultRequestGetsJsonAndAuthSnapshot() {
        client.newCall(Request.Builder().url("https://fixture.invalid/data/").build()).execute().close()
        assertEquals("application/json", captured!!.header("Accept"))
        assertEquals("Bearer fixture-access", captured!!.header("Authorization"))
        assertNotNull(captured!!.tag(AuthSnapshot::class.java))
    }
    @Test fun explicitMediaTypeIsPreserved() {
        client.newCall(Request.Builder().url("https://fixture.invalid/export/")
            .header("Accept", "text/plain").build()).execute().close()
        assertEquals("text/plain", captured!!.header("Accept"))
        assertEquals("Bearer fixture-access", captured!!.header("Authorization"))
    }
    @Test fun optOutDoesNotAddAuthentication() {
        client.newCall(Request.Builder().url("https://fixture.invalid/auth/")
            .header("No-Auth", "true").header("Accept", "text/plain").build()).execute().close()
        assertNull(captured!!.header("Authorization"))
        assertNull(captured!!.header("No-Auth"))
        assertEquals("text/plain", captured!!.header("Accept"))
    }
    @Test fun globalStreamSurvivesRealAuthInterceptor() = runBlocking {
        val events = globalAskStream(client, "https://fixture.invalid", "budget", "meetings").toList()
        assertEquals("text/event-stream", captured!!.header("Accept"))
        assertEquals("Bearer fixture-access", captured!!.header("Authorization"))
        assertTrue(events.first() is AskEvent.Meta)
        assertTrue(events.last() is AskEvent.Done)
        assertFalse(events.any { it is AskEvent.Failure })
    }
}
