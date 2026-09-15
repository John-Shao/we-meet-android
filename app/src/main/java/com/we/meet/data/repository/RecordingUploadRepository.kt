package com.we.meet.data.repository

import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.api.RecordingUploadRetry
import com.we.meet.data.api.RecordingUploadState
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.io.IOException
import java.util.UUID

/** Streams the selected document with a byte limit; never caches private media or results. */
class RecordingUploadRepository(private val api: RecordingUploadApi, private val currentViewer: () -> String?) {
    suspend fun capabilities(viewer: String) = scoped(viewer) {
        api.capabilities().also { require(!it.available || (it.maxBytes > 0 && it.extensions.isNotEmpty())) }
    }

    suspend fun upload(
        viewer: String, key: String, name: String, size: Long?, config: RecordingUploadCapabilities,
        context: String, hotwords: String, open: () -> InputStream,
    ): Result<RecordingUploadState> = scoped(viewer) {
        uuid(key)
        require(config.available && config.maxBytes > 0)
        require(size == null || size in 1..config.maxBytes)
        require(name.substringAfterLast('.', "").lowercase() in config.extensions)
        require(context.length <= 400 && hotwords.length <= 4000)
        require(hotwords.lines().filter { it.isNotBlank() }.let { words -> words.size <= 100 && words.all { it.trim().length <= 40 } })
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size ?: -1
            override fun writeTo(sink: BufferedSink) {
                var total = 0L
                open().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (currentViewer() != viewer) throw IOException("Account changed")
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > config.maxBytes) throw IOException("File exceeds upload limit")
                        sink.write(buffer, 0, count)
                    }
                }
                if (total == 0L || (size != null && total != size)) throw IOException("File changed or empty")
            }
        }
        val text = "text/plain".toMediaType()
        api.upload(key.toRequestBody(text), MultipartBody.Part.createFormData("audio", name, body),
            context.toRequestBody(text), hotwords.toRequestBody(text)).also(::validate)
    }

    suspend fun state(viewer: String, recordId: String) = scoped(viewer) {
        uuid(recordId)
        api.state(recordId).also { validate(it); require(it.recordId == recordId) }
    }

    suspend fun retry(viewer: String, recordId: String, attempt: Int) = scoped(viewer) {
        uuid(recordId)
        require(attempt > 0)
        api.retry(recordId, RecordingUploadRetry(attempt)).also { validate(it); require(it.recordId == recordId) }
    }

    private fun validate(state: RecordingUploadState) {
        uuid(state.recordId)
        require(state.attempt > 0 && state.status in setOf("queued", "submitting", "running", "succeeded", "failed"))
    }
    private fun uuid(value: String) { require(UUID.fromString(value).toString().equals(value, true)) }
    private suspend fun <T> scoped(viewer: String, action: suspend () -> T): Result<T> = try {
        require(viewer.isNotBlank() && currentViewer() == viewer)
        val result = action()
        require(currentViewer() == viewer)
        Result.success(result)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: Exception) { Result.failure(error) }
}
