package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.*
import com.we.meet.data.repository.RecordingUploadRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecordingUploadHttpTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun repository(client: OkHttpClient, port: Int): RecordingUploadRepository {
        val api = Retrofit.Builder().baseUrl("http://127.0.0.1:$port/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(RecordingUploadApi::class.java)
        return RecordingUploadRepository(api) { "owner" }
    }

    @Test fun acceptedUploadWithSlowStorageOutlivesTheGeneralApiTimeout() = runBlocking {
        val base = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val upload = recordingUploadHttp(base)
        // General requests retain their original timeout; auth interceptors are inherited.
        assertEquals(30_000, base.readTimeoutMillis)
        val peers = Executors.newFixedThreadPool(2)
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val receipts = (1..2).map {
                    peers.submit<String> {
                        server.accept().use { connection ->
                            connection.soTimeout = 5000
                            val input = connection.getInputStream()
                            val headers = StringBuilder()
                            while (!headers.endsWith("\r\n\r\n")) {
                                check(headers.length < 16384)
                                val next = input.read(); check(next >= 0); headers.append(next.toChar())
                            }
                            assertTrue(headers.startsWith("POST /api/v1.0/recording-uploads/ HTTP/1.1"))
                            val size = headers.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            val body = input.readNBytes(size).toString(Charsets.UTF_8)
                            assertTrue(body.contains(id))
                            assertTrue(body.contains("filename=\"Meeting.MP4\""))
                            assertTrue(body.contains("video fixture"))
                            // The upload is accepted, but object storage delays the HTTP response.
                            Thread.sleep(31_000)
                            val json = """{"record_id":"$id","status":"queued","attempt":1,"retryable":false,"error_code":"","model":"fixture"}"""
                            try {
                                connection.getOutputStream().apply {
                                    write(("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\nConnection: close\r\n\r\n" + json).toByteArray())
                                    flush()
                                }
                            } catch (_: java.io.IOException) { /* The old client has already timed out. */ }
                            body
                        }
                    }
                }
                suspend fun send(client: OkHttpClient) = repository(client, server.localPort).upload(
                    "owner", id, "Meeting.MP4", 13, RecordingUploadCapabilities(true, 1024, listOf("mp4")), "", "",
                ) { "video fixture".byteInputStream() }
                val oldResult = async { send(base) }
                val newResult = async { send(upload) }
                assertTrue(oldResult.await().exceptionOrNull() is SocketTimeoutException)
                assertEquals(RecordingUploadState(id, "queued", 1), newResult.await().getOrThrow())
                receipts.forEach { it.get(5, TimeUnit.SECONDS) }
            }
        } finally {
            peers.shutdownNow()
            base.dispatcher.executorService.shutdownNow()
            base.connectionPool.evictAll()
        }
    }
}
