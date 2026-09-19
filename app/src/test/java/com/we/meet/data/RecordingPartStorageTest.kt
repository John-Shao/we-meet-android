package com.we.meet.data

import com.we.meet.data.api.OkHttpPartStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The part PUT, against a real socket.
 *
 * Two things here cannot be checked with a fake. The first is that the ETag
 * survives: `CompleteMultipartUpload` needs every part's ETag, so a quoted or
 * dropped header means the upload can never be finished, and only a real HTTP
 * response proves the parsing. The second is that the bytes actually go up —
 * `contentLength` is part of the request, not decoration.
 */
class RecordingPartStorageTest {

    /** Serves one request and replies with [response], returning what it read. */
    private fun serve(
        response: String,
        inspect: (headers: String, body: ByteArray) -> Unit,
    ): Pair<String, () -> Unit> {
        val pool = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val url = "http://127.0.0.1:${server.localPort}/part/1"
        val served = pool.submit {
            server.accept().use { connection ->
                connection.soTimeout = 10_000
                val input = connection.getInputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    check(headers.length < 16384)
                    val next = input.read()
                    check(next >= 0)
                    headers.append(next.toChar())
                }
                val length = headers.lines()
                    .first { it.startsWith("Content-Length:", true) }
                    .substringAfter(':').trim().toInt()
                val body = input.readNBytes(length)
                inspect(headers.toString(), body)
                connection.getOutputStream().apply {
                    write(response.toByteArray())
                    flush()
                }
            }
        }
        return url to { served.get(10, TimeUnit.SECONDS); server.close(); pool.shutdown() }
    }

    @Test
    fun sendsThePartAndReturnsTheUnquotedEtag() {
        val body = "part-one-bytes".toByteArray()
        var seen = 0L
        val (url, finish) = serve(
            "HTTP/1.1 200 OK\r\nETag: \"abc123\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        ) { headers, sent ->
            // PUT, and the declared length is what the signature covers.
            check(headers.startsWith("PUT /part/1 HTTP/1.1"))
            check(headers.contains("Content-Length: ${body.size}", true))
            // No app credentials may travel to a storage host.
            check(!headers.contains("Authorization", true))
            assertEquals(body.size, sent.size)
        }
        try {
            val etag = OkHttpPartStorage().putPart(
                url = url,
                size = body.size.toLong(),
                open = { ByteArrayInputStream(body) },
                onProgress = { seen = it },
                cancel = { false },
            )
            // Unquoted: the value is used verbatim in the completion payload.
            assertEquals("abc123", etag)
            assertEquals(body.size.toLong(), seen)
        } finally {
            finish()
        }
    }

    @Test
    fun aMissingEtagIsAFailureRatherThanAnEmptyString() {
        // A bucket that does not expose the header cannot complete an upload, and
        // an empty ETag would be sent to the server as if it were real.
        val (url, finish) = serve(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        ) { _, _ -> }
        try {
            assertNull(
                OkHttpPartStorage().putPart(
                    url = url,
                    size = 1,
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    onProgress = {},
                    cancel = { false },
                )
            )
        } finally {
            finish()
        }
    }

    @Test
    fun aRefusedPartHasNoEtag() {
        val (url, finish) = serve(
            "HTTP/1.1 403 Forbidden\r\nETag: \"should-be-ignored\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        ) { _, _ -> }
        try {
            assertNull(
                OkHttpPartStorage().putPart(
                    url = url,
                    size = 1,
                    open = { ByteArrayInputStream(byteArrayOf(1)) },
                    onProgress = {},
                    cancel = { false },
                )
            )
        } finally {
            finish()
        }
    }
}
