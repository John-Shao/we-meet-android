package com.we.meet.ui.records

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.auth.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AuthSessionFenceTest {
    private val store = TokenStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun login(name: String) { store.accessToken = name; store.refreshToken = "$name-refresh" }
    private fun rejected(snapshot: AuthSnapshot = store.authSnapshot(), token: String? = snapshot.access) = Response.Builder()
        .request(Request.Builder().url("https://fixture.invalid/api/meeting-records/record/")
            .tag(AuthSnapshot::class.java, snapshot).header("Authorization", "Bearer $token").build())
        .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
    @After fun cleanup() = store.clear()
    @Test fun oldRequestNeverRefreshesOrRetriesAsNewLogin() {
        login("old"); val response = rejected(); login("new")
        val auth = TokenRefreshAuthenticator(store) { error("must not refresh") }
        assertNull(auth.authenticate(null, response)); assertEquals("new", store.accessToken)
    }
    @Test fun lateRefreshCannotOverwriteNewLogin() {
        login("old"); val response = rejected()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val auth = TokenRefreshAuthenticator(store) {
                entered.countDown(); check(finish.await(10, TimeUnit.SECONDS))
                TokenRefreshAuthenticator.RefreshedTokens("late-old", "late-refresh")
            }
            val result = executor.submit<Request?> { auth.authenticate(null, response) }
            assertTrue(entered.await(10, TimeUnit.SECONDS)); login("new"); finish.countDown()
            assertNull(result.get(10, TimeUnit.SECONDS)); assertEquals("new", store.accessToken)
        } finally { finish.countDown(); executor.shutdownNow() }
    }
    @Test fun logoutFencesPendingRefreshAndOldUnauthorizedResponse() {
        login("old"); val snapshot = store.authSnapshot(); store.clear()
        assertFalse(store.rotate(snapshot, "late", "late-refresh"))
        assertFalse(store.expire(snapshot, "old")); assertNull(store.accessToken)
    }
    @Test fun concurrentSameLoginUsesRotatedTokenWithoutAnotherRefresh() {
        login("old"); val response = rejected(); val snapshot = store.authSnapshot()
        assertTrue(store.rotate(snapshot, "fresh", "fresh-refresh"))
        val auth = TokenRefreshAuthenticator(store) { error("must share refresh") }
        assertEquals("Bearer fresh", auth.authenticate(null, response)!!.header("Authorization"))
        assertFalse(store.expire(snapshot, "old")); assertEquals("fresh", store.accessToken)
    }
    @Test fun successfulRefreshKeepsSessionAndOnlyRetriesOnce() {
        login("old"); val response = rejected(); val snapshot = store.authSnapshot()
        val auth = TokenRefreshAuthenticator(store) { TokenRefreshAuthenticator.RefreshedTokens("fresh", "rotated") }
        val retry = auth.authenticate(null, response)!!
        assertEquals(snapshot.session, store.authSnapshot().session)
        assertEquals("Bearer fresh", retry.header("Authorization"))
        val second = response.newBuilder().request(retry).priorResponse(response).build()
        assertNull(auth.authenticate(null, second))
        assertTrue(store.expire(snapshot, "fresh")); assertNull(store.accessToken)
    }
    @Test fun lateUnauthorizedCannotExpireNewLoginEvenWithSameTokenText() {
        login("same"); val original = store.authSnapshot(); login("same")
        assertFalse(store.expire(original, "same")); assertEquals("same", store.accessToken)
    }
}
