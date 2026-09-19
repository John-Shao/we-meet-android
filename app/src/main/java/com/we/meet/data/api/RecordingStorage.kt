package com.we.meet.data.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Puts the selected bytes into object storage under a URL the server signed.
 *
 * A seam rather than a direct call, for two reasons. Tests cannot reach a bucket,
 * and — more importantly — this is the one request in the app that goes to a
 * third-party host, so where it is allowed to send credentials is worth being
 * able to assert.
 */
fun interface RecordingStorage {
    /**
     * @return true when the object is stored. A false or thrown failure means the
     *   caller must ask for a fresh ticket, because the signature may be spent.
     */
    fun put(url: String, headers: Map<String, String>, size: Long, open: () -> InputStream): Boolean
}

/**
 * A part PUT, which additionally has to report progress and return its ETag.
 *
 * Separate from [RecordingStorage] because a whole-file PUT needs neither: its
 * result is only success or failure, while a part's ETag is required to finish
 * the upload at all.
 */
fun interface RecordingPartStorage {
    /**
     * @param onProgress bytes sent so far, for a caller that wants to show it.
     * @return the part's ETag, or null when storage refused or did not expose it.
     */
    fun putPart(
        url: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
        cancel: () -> Boolean,
    ): String?
}

/**
 * A client with no `AuthInterceptor`, no `SessionExpiredInterceptor` and no
 * authenticator.
 *
 * A presigned URL carries its own authorization in the query string. Sending our
 * bearer token to a storage host would leak an app credential to a third party
 * for no benefit, and a storage 401 is not a session expiry — retrying it with a
 * refreshed token cannot help, so the refresh machinery would only be noise.
 */
internal fun recordingStorageHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.MINUTES)
    .writeTimeout(10, TimeUnit.MINUTES)
    .callTimeout(60, TimeUnit.MINUTES)
    .followRedirects(false)
    .followSslRedirects(false)
    .cache(null)
    .build()

/** Streams the file without ever holding it in memory. */
internal class HttpRecordingStorage(private val client: OkHttpClient = recordingStorageHttp()) : RecordingStorage {
    override fun put(url: String, headers: Map<String, String>, size: Long, open: () -> InputStream): Boolean {
        val declared = requireNotNull(headers["Content-Type"]) { "A signed PUT needs its content type" }
        val body = object : RequestBody() {
            override fun contentType() = declared.toMediaType()
            // The signature covers ContentLength, so an unknown size cannot be
            // signed for and must not be sent as a chunked body.
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                var total = 0L
                open().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        sink.write(buffer, 0, count)
                    }
                }
                if (total != size) throw IOException("File changed while uploading")
            }
        }
        val request = Request.Builder().url(url).put(body).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }
}

/**
 * Streams one part, reporting progress and reading back the ETag.
 *
 * The ETag is the point: `CompleteMultipartUpload` needs every part's ETag, and
 * only this response carries it. A bucket that does not return the header makes
 * a chunked upload impossible, which is why a missing one is a failure here
 * rather than something to paper over.
 */
internal class OkHttpPartStorage(
    private val client: OkHttpClient = recordingStorageHttp(),
) : RecordingPartStorage {
    override fun putPart(
        url: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
        cancel: () -> Boolean,
    ): String? {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                var total = 0L
                open().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel()) throw IOException("Upload cancelled")
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        sink.write(buffer, 0, count)
                        onProgress(total)
                    }
                }
                if (total != size) throw IOException("Part changed while uploading")
            }
        }
        val request = Request.Builder().url(url).put(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // OkHttp exposes every response header, so no bucket CORS exposure
            // rule is needed on this client — unlike a browser.
            response.header("ETag")?.trim('"')
        }
    }
}
