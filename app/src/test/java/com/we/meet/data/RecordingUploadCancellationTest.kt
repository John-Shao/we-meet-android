package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.*
import com.we.meet.data.repository.RecordingUploadRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real stalled HTTP requests: cancellation must close the socket, not just hide the dialog. */
class RecordingUploadCancellationTest {
    private fun stalledUpload(direct: Boolean) = runBlocking {
        val received = CompletableDeferred<Unit>()
        val pool = Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val served = pool.submit<Int> {
                    server.accept().use { connection ->
                        connection.soTimeout = 5000
                        val input = connection.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val next = input.read(); check(next >= 0); headers.append(next.toChar())
                        }
                        val size = headers.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        assertEquals(size, input.readNBytes(size).size)
                        if (direct) {
                            assertTrue(headers.startsWith("PUT"))
                            assertFalse(headers.contains("Authorization", true))
                            assertTrue(headers.contains("x-amz-acl: private", true))
                        }
                        received.complete(Unit)
                        input.read() // No HTTP response: the client must actively close this connection.
                    }
                }
                val base = "http://127.0.0.1:${server.localPort}/"
                val progress = java.util.concurrent.atomic.AtomicLong()
                val request = async {
                    if (direct) HttpRecordingStorage(client).putWithProgress(
                        base, mapOf("Content-Type" to "audio/wav", "x-amz-acl" to "private"), 5,
                        { "audio".byteInputStream() }, { progress.set(it) },
                    ) else {
                        val api = Retrofit.Builder().baseUrl(base).client(client)
                            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
                            .build().create(RecordingUploadApi::class.java)
                        RecordingUploadRepository(api, { "owner" }).uploadWithProgress(
                            "owner", "11111111-1111-4111-8111-111111111111", "Audio.wav", 5,
                            RecordingUploadCapabilities(true, 1024, listOf("wav")), "", "", { "audio".byteInputStream() },
                            { sent, _ -> progress.set(sent) },
                        )
                    }
                }
                withTimeout(5000) { received.await(); request.cancelAndJoin() }
                assertTrue(request.isCancelled)
                assertEquals(5L, progress.get())
                assertEquals(-1, served.get(5, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun directPutCancelsWhileWaitingForStorage() { stalledUpload(true) }
    @Test fun multipartPostCancelsWhileWaitingForRegistration() { stalledUpload(false) }
}
