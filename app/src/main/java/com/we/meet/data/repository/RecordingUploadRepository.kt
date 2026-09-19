package com.we.meet.data.repository

import com.we.meet.data.api.RecordingStorage
import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.api.RecordingUploadComplete
import com.we.meet.data.api.RecordingUploadPresign
import com.we.meet.data.api.RecordingUploadRetry
import com.we.meet.data.api.RecordingUploadState
import com.we.meet.data.api.RecordingUploadTicket
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
class RecordingUploadRepository(
    private val api: RecordingUploadApi,
    private val currentViewer: () -> String?,
    /**
     * Null until a storage client is wired in. Null means the presigned path is
     * unavailable and imports stay on multipart, so existing callers and tests
     * keep working without pretending to have a bucket.
     */
    private val storage: RecordingStorage?,
) {
    /** Multipart only, for callers with no storage client. */
    constructor(api: RecordingUploadApi, currentViewer: () -> String?) : this(api, currentViewer, null)



    /**
     * 上一次拿到的上传能力表,按 viewer 分开存。能力表只说明「允许哪些后缀、多大」,
     * 不含任何记录内容,所以进程内记住它是安全的;而「AI 录音」/「会议实录」两个页面
     * 每次进入都要靠它决定「导入」入口画不画,不缓存就等于每次进页面都先空一格等网络。
     */
    private val capabilitiesByViewer = java.util.concurrent.ConcurrentHashMap<String, RecordingUploadCapabilities>()

    /** 上一次已知的能力表;没有或已换账号就是 null。 */
    fun lastCapabilities(viewer: String): RecordingUploadCapabilities? =
        if (viewer.isNotBlank() && currentViewer() == viewer) capabilitiesByViewer[viewer] else null

    suspend fun capabilities(viewer: String) = scoped(viewer) {
        api.capabilities().also {
            require(!it.available || (it.maxBytes > 0 && it.extensions.isNotEmpty()))
            require(currentViewer() == viewer)
            capabilitiesByViewer[viewer] = it
        }
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

    /** True when a file this large can only travel by the presigned path. */
    fun needsDirectUpload(size: Long?, config: RecordingUploadCapabilities): Boolean =
        size != null && directUploadEnabled(config) && size > config.maxBytes

    private fun directUploadEnabled(config: RecordingUploadCapabilities): Boolean =
        storage != null && config.directUploadAvailable && config.directMaxBytes > 0

    /**
     * The ceiling the picker validates against: the larger of the two, but only
     * when the presigned path is actually reachable.
     */
    fun maxBytes(config: RecordingUploadCapabilities): Long =
        if (directUploadEnabled(config)) maxOf(config.maxBytes, config.directMaxBytes) else config.maxBytes

    /**
     * Import by presigned direct upload: sign, PUT to storage, then adopt.
     *
     * The two steps are not atomic, so a failure after the PUT is recovered by
     * re-sending completion with the same declaration — hence [ticket]. Minting a
     * new ticket instead would strand the first object in the bucket and re-upload
     * bytes that already landed.
     *
     * The PUT goes to the storage host through a client that carries no app
     * credentials; the signed URL is its own authorization (see `RecordingStorage`).
     */
    suspend fun uploadDirect(
        viewer: String, key: String, name: String, size: Long, config: RecordingUploadCapabilities,
        context: String, hotwords: String, open: () -> InputStream,
        ticket: RecordingUploadTicket?, contentType: String,
    ): Result<RecordingUploadState> = scoped(viewer) {
        uuid(key)
        require(directUploadEnabled(config))
        require(size in 1..config.directMaxBytes)
        require(name.substringAfterLast('.', "").lowercase() in config.extensions)
        require(context.length <= 400 && hotwords.length <= 4000)
        require(contentType.isNotBlank() && contentType.length <= 128)
        val signed = ticket ?: api.presign(
            RecordingUploadPresign(key, name, size, contentType, context, hotwords)
        ).also {
            require(it.storageName.isNotBlank() && it.uploadUrl.startsWith("https://"))
            require(it.headers["Content-Type"] == contentType)
        }
        if (!requireNotNull(storage).put(signed.uploadUrl, signed.headers, size, open)) {
            throw IOException("Object storage refused the upload")
        }
        require(currentViewer() == viewer)
        api.complete(
            RecordingUploadComplete(key, name, size, contentType, signed.storageName, context, hotwords)
        ).also(::validate)
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
