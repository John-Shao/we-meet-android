package com.we.meet.feature.docs.data.net

import coil.ImageLoader
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.data.DocsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DocsSessionRecoveryTest {
    @Test fun lateAnonymousPageIsRetriedAfterAnotherRequestRenewsTheSession() = runBlocking {
        Fixture(delayFirstPage = true).use { fixture ->
            val pending = async(Dispatchers.IO) { fixture.repo.list(1) }
            try {
                assertTrue(fixture.pageEntered.await(5, TimeUnit.SECONDS))
                assertEquals("doc", fixture.repo.list(1, pageSize = 1).results.single().id)
            } finally {
                fixture.releasePage.countDown()
            }
            assertEquals("doc", pending.await().results.single().id)
            assertEquals(1, fixture.tickets.get())
            assertEquals(4, fixture.pages.get())
        }
    }

    @Test fun expiredSessionReturningSuccessfulEmptyListIsRenewed() = runBlocking {
        Fixture().use { fixture ->
            assertEquals("doc", fixture.repo.list(1).results.single().id)
            assertEquals(1, fixture.tickets.get())
            assertEquals(1, fixture.probes.get())
            assertEquals(2, fixture.pages.get())
            assertEquals("fresh", fixture.credentials.sessionId)
        }
    }

    @Test fun authenticatedEmptyPageDoesNotRenewSession() = runBlocking {
        Fixture(initiallyAuthenticated = true, empty = true).use { fixture ->
            assertTrue(fixture.repo.list(1, isFavorite = true).results.isEmpty())
            assertEquals(0, fixture.tickets.get())
            assertEquals(1, fixture.probes.get())
            assertEquals(1, fixture.pages.get())
        }
    }

    @Test fun populatedPageDoesNotNeedSessionProbe() = runBlocking {
        Fixture(initiallyAuthenticated = true).use { fixture ->
            assertEquals("doc", fixture.repo.list(1).results.single().id)
            assertEquals(0, fixture.tickets.get())
            assertEquals(0, fixture.probes.get())
        }
    }

    @Test fun renewedSessionCanHaveALegitimatelyEmptyPage() = runBlocking {
        Fixture(empty = true).use { fixture ->
            assertTrue(fixture.repo.list(1).results.isEmpty())
            assertEquals(1, fixture.tickets.get())
            assertEquals(2, fixture.probes.get())
        }
    }

    @Test fun recoveryFailureIsAnErrorInsteadOfAnEmptyPageAndRetriesOnlyOnce() = runBlocking {
        Fixture(recoveryWorks = false).use { fixture ->
            val error = runCatching { fixture.repo.list(1) }.exceptionOrNull()
            assertTrue(error is HttpException)
            assertEquals(403, (error as HttpException).code())
            assertEquals(1, fixture.tickets.get())
            assertEquals(2, fixture.probes.get())
            assertEquals(2, fixture.pages.get())
        }
    }

    @Test fun probeServerFailureIsNotTreatedAsAnEmptyPageOrAuthenticationFailure() = runBlocking {
        Fixture(probeStatus = 503).use { fixture ->
            val error = runCatching { fixture.repo.list(1) }.exceptionOrNull()
            assertTrue(error is HttpException)
            assertEquals(503, (error as HttpException).code())
            assertEquals(0, fixture.tickets.get())
            assertEquals(1, fixture.pages.get())
        }
    }

    @Test fun searchTrashAndChildrenAlsoRecoverFromAnonymousEmptyPages() = runBlocking {
        val queries: List<suspend (DocsRepository) -> DocsPageDto> = listOf(
            { it.search("document") }, { it.trashbin(1) }, { it.children("parent", 1) },
        )
        for (query in queries) Fixture().use { fixture ->
            assertEquals("doc", query(fixture.repo).results.single().id)
            assertEquals(1, fixture.tickets.get())
        }
    }

    private class MemoryCredentials : DocsCredentials {
        override var sessionId: String? = null
        override var csrfToken: String? = null
        override var ownerKey: String? = null
        override fun clear() { sessionId = null; csrfToken = null }
    }

    /** Real cookie transport, including the server's anonymous HTTP 200 contract. */
    private class Fixture(
        private val initiallyAuthenticated: Boolean = false,
        private val empty: Boolean = false,
        private val recoveryWorks: Boolean = true,
        private val probeStatus: Int? = null,
        private val delayFirstPage: Boolean = false,
    ) : AutoCloseable {
        val pageEntered = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        val tickets = AtomicInteger()
        val probes = AtomicInteger()
        val pages = AtomicInteger()
        val credentials = MemoryCredentials()
        private val server = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool()
        private val base = "http://127.0.0.1:${server.localPort}"
        private val hostClient = OkHttpClient.Builder().addInterceptor { chain ->
            tickets.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"url":"$base/ticket"}""".toResponseBody()).build()
        }.build()
        private val deps = object : DocsDeps {
            override val docsAccountKey = "account"
            override val docsBaseUrl = base
            override val baseUrl = "https://meet.example/"
            override val authedOkHttp = hostClient
            override val docsRepository: DocsRepository get() = error("not used")
            override val docsMediaLoader: ImageLoader get() = error("not used")
        }
        private val manager = DocsSessionManager(deps, credentials)
        private val docsClient: OkHttpClient
        val repo = DocsRepository(manager)

        init {
            manager.generation
            credentials.sessionId = "persisted"
            docsClient = manager.okHttp
            executor.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    executor.execute {
                        socket.use {
                            it.soTimeout = 5_000
                            val reader = it.getInputStream().bufferedReader()
                            val path = reader.readLine().split(" ")[1].substringBefore('?')
                            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                            val authenticated = initiallyAuthenticated ||
                                (recoveryWorks && headers.any { header ->
                                    header.startsWith("Cookie:", ignoreCase = true) && header.contains("docs_sessionid=fresh")
                                })
                            var status = 200
                            var cookie = ""
                            val body = when (path) {
                                "/ticket" -> {
                                    cookie = "Set-Cookie: docs_sessionid=fresh; Path=/\r\n"
                                    "{}"
                                }
                                "/api/v1.0/users/me/" -> {
                                    probes.incrementAndGet()
                                    status = probeStatus ?: if (authenticated) 200 else 403
                                    if (status == 200) """{"id":"account"}"""
                                    else """{"detail":"Authentication credentials were not provided."}"""
                                }
                                else -> {
                                    if (pages.incrementAndGet() == 1 && delayFirstPage) {
                                        pageEntered.countDown()
                                        check(releasePage.await(5, TimeUnit.SECONDS))
                                    }
                                    if (authenticated && !empty) """{"count":1,"results":[{"id":"doc"}]}"""
                                    else """{"count":0,"results":[]}"""
                                }
                            }
                            val bytes = body.toByteArray()
                            it.getOutputStream().write(("HTTP/1.1 $status Response\r\n" +
                                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n$cookie\r\n").toByteArray() + bytes)
                        }
                    }
                }
            }
        }

        override fun close() {
            releasePage.countDown()
            manager.invalidate()
            server.close()
            executor.shutdownNow()
            listOf(hostClient, docsClient).forEach {
                it.dispatcher.executorService.shutdownNow()
                it.connectionPool.evictAll()
            }
        }
    }
}
