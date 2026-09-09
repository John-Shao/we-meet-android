package com.we.meet.feature.docs.data.net

import coil.ImageLoader
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.data.DocsRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DocsSessionBoundaryTest {
    private class MemoryCredentials : DocsCredentials {
        override var sessionId: String? = null
        override var csrfToken: String? = null
        override var ownerKey: String? = null
        override fun clear() { sessionId = null; csrfToken = null }
    }

    @Test fun accountSwitchCancelsFailedMemberGrantAndRejectsRemainingBatches() = runBlocking {
        val account = AtomicReference("A")
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            account.set("B")
            throw java.io.IOException("response lost during account switch")
        }.build()
        val deps = object : DocsDeps {
            override val docsAccountKey get() = account.get()
            override val docsBaseUrl = "https://docs.example/"
            override val baseUrl = "https://meet.example/"
            override val authedOkHttp = client
            override val docsRepository: DocsRepository get() = error("not used")
            override val docsMediaLoader: ImageLoader get() = error("not used")
        }
        val manager = DocsSessionManager(deps, MemoryCredentials())
        val expected = manager.generation
        try {
            assertTrue(runCatching { manager.addMembers("doc", listOf("one"), "reader", expected) }
                .exceptionOrNull() is CancellationException)
            assertTrue(runCatching { manager.addMembers("doc", listOf("two"), "reader", expected) }
                .exceptionOrNull() is CancellationException)
            assertEquals(1, calls)
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun repositoryRejectsAnOldAccountResultEvenWithoutExplicitInvalidation() = runBlocking {
        val account = AtomicReference("A")
        val client = OkHttpClient()
        val deps = object : DocsDeps {
            override val docsAccountKey get() = account.get()
            override val docsBaseUrl = "https://docs.example/"
            override val baseUrl = "https://meet.example/"
            override val authedOkHttp = client
            override val docsRepository: DocsRepository get() = error("not used")
            override val docsMediaLoader: ImageLoader get() = error("not used")
        }
        val credentials = MemoryCredentials()
        val manager = DocsSessionManager(deps, credentials)
        manager.generation // Establish the persisted account owner before seeding a session.
        credentials.sessionId = "A"
        val repository = DocsRepository(manager)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            runCatching { repository.docsCall { entered.complete(Unit); release.await(); "A-private-content" } }
        }
        entered.await()
        account.set("B")
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertNull(credentials.sessionId)
        assertTrue(credentials.ownerKey!!.endsWith("|B"))
        manager.invalidate()
    }

    @Test fun oldBootstrapCannotRestoreCookiesAfterAccountSwitch() = runBlocking {
        val account = AtomicReference("A")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = ServerSocket(0)
        val executor = Executors.newCachedThreadPool()
        executor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val oldAccount = reader.readLine().contains("/ticket-A ")
                        while (!reader.readLine().isNullOrEmpty()) { }
                        if (oldAccount) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                        val cookie = if (oldAccount) "A" else "B"
                        runCatching { it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\nSet-Cookie: docs_sessionid=$cookie; Path=/\r\n\r\n".toByteArray()) }
                    }
                }
            }
        }
        val base = "http://127.0.0.1:${server.localPort}"
        val hostClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"url":"$base/ticket-${account.get()}"}""".toResponseBody()).build()
        }.build()
        val deps = object : DocsDeps {
            override val docsAccountKey get() = account.get()
            override val docsBaseUrl = base
            override val baseUrl = "https://meet.example/"
            override val authedOkHttp = hostClient
            override val docsRepository: DocsRepository get() = error("not used")
            override val docsMediaLoader: ImageLoader get() = error("not used")
        }
        val credentials = MemoryCredentials()
        val manager = DocsSessionManager(deps, credentials)
        val oldGeneration = manager.generation
        try {
            val pending = async(Dispatchers.IO) { runCatching { manager.ensureSession(oldGeneration) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.invalidate()
            account.set("B")
            release.countDown()
            assertTrue(pending.await().isFailure)
            assertFalse(manager.isCurrent(oldGeneration))
            assertNull(credentials.sessionId)
            manager.ensureSession()
            assertEquals("B", credentials.sessionId)
            assertTrue(credentials.ownerKey!!.endsWith("|B"))
            assertNotNull(credentials.csrfToken)
            manager.invalidate()
            assertNull(credentials.sessionId)
        } finally {
            release.countDown()
            server.close()
            executor.shutdownNow()
            hostClient.dispatcher.executorService.shutdown()
        }
    }
}
