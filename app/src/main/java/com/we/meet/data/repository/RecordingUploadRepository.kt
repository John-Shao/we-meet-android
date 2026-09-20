package com.we.meet.data.repository

import com.we.meet.data.api.RecordingPartStorage
import com.we.meet.data.api.RecordingStorage
import com.we.meet.data.api.RecordingUploadApi
import com.we.meet.data.api.RecordingUploadBegin
import com.we.meet.data.api.RecordingUploadCapabilities
import com.we.meet.data.api.RecordingUploadComplete
import com.we.meet.data.api.RecordingUploadFinish
import com.we.meet.data.api.RecordingUploadHeldPart
import com.we.meet.data.api.RecordingUploadPartTag
import com.we.meet.data.api.RecordingUploadPlan
import com.we.meet.data.api.RecordingUploadPresign
import com.we.meet.data.api.RecordingUploadRetry
import com.we.meet.data.api.RecordingUploadSign
import com.we.meet.data.api.RecordingUploadState
import com.we.meet.data.api.RecordingUploadTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.io.IOException
import java.util.UUID

/**
 * A deliberate stop, distinct from a failure.
 *
 * Deliberately *not* `CancellationException`: that type is the coroutine
 * machinery's own signal and has to propagate untouched, so reusing it here would
 * make "the reader pressed cancel" indistinguishable from "the scope is going
 * away" and the failure would escape as a thrown exception instead of a result.
 */
class RecordingUploadCancelled : RuntimeException("Upload cancelled")

/** Part URLs asked for in one request, so a large file costs few requests. */
private const val SIGN_BATCH = 24

/**
 * One chunked import's declaration and byte source.
 *
 * Bundled rather than passed as a long argument list, and because these fields
 * only mean anything together: the declaration is what the server signs, and the
 * offsets are only valid against that same size.
 */
data class ChunkedUploadRequest(
    val viewer: String,
    val key: String,
    val name: String,
    val size: Long,
    val config: RecordingUploadCapabilities,
    val context: String,
    val hotwords: String,
    val contentType: String,
    /** A session id the caller remembered; a hint, not a source of truth. */
    val resumeFrom: String? = null,
    /** Opens the byte range for one part, in file order. */
    val openAt: (offset: Long, length: Long) -> InputStream,
    /** Bytes genuinely stored, so a resume starts from what already landed. */
    val onProgress: (sent: Long, total: Long) -> Unit,
    val cancelled: () -> Boolean,
    val diarization: Boolean = false,
)

/** Streams the selected document with a byte limit; never caches private media or results. */
class RecordingUploadRepository(
    private val api: RecordingUploadApi,
    private val currentViewer: () -> String?,
    /**
     * Null until a storage client is wired in. Null means the presigned path is
     * unavailable and imports stay on multipart, so existing callers and tests
     * keep working without pretending to have a bucket.
     */
    private val storage: RecordingStorage? = null,
    /**
     * Part PUTs, needed only by the chunked path. Null keeps chunking
     * unavailable, so a caller wired for the whole-file path still works.
     */
    private val partStorage: RecordingPartStorage? = null,
) {
    suspend fun personalHotwords(viewer: String) = scoped(viewer) {
        api.personalHotwords().also(::validatePersonalHotwords)
    }

    suspend fun savePersonalHotwords(viewer: String, text: String, revision: Int) = scoped(viewer) {
        require(revision >= 0 && text.codePointCount(0, text.length) <= 4000)
        mergePersonalHotwords(text, emptyList())
        api.savePersonalHotwords(com.we.meet.data.api.PersonalHotwordsRequest(text, revision)).also(::validatePersonalHotwords)
    }

    private fun validatePersonalHotwords(value: com.we.meet.data.api.PersonalHotwordsDto) {
        require(value.revision >= 0 && value.words.size <= 100)
        require(value.words.all { it.isNotBlank() && it.trim() == it })
        require(mergePersonalHotwords("", value.words) == value.words.joinToString("\n"))
    }



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
    ): Result<RecordingUploadState> = uploadWithProgress(viewer, key, name, size, config, context, hotwords, open) { _, _ -> }

    suspend fun uploadWithProgress(
        viewer: String, key: String, name: String, size: Long?, config: RecordingUploadCapabilities,
        context: String, hotwords: String, open: () -> InputStream,
        diarization: Boolean = false,
        onProgress: (Long, Long) -> Unit,
    ): Result<RecordingUploadState> = scoped(viewer) {
        val transferContext = currentCoroutineContext()
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
                        if (!transferContext.isActive) throw IOException("Upload stopped")
                        if (currentViewer() != viewer) throw IOException("Account changed")
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > config.maxBytes) throw IOException("File exceeds upload limit")
                        sink.write(buffer, 0, count)
                        onProgress(total, size ?: 0)
                    }
                }
                if (total == 0L || (size != null && total != size)) throw IOException("File changed or empty")
            }
        }
        val text = "text/plain".toMediaType()
        api.upload(key.toRequestBody(text), MultipartBody.Part.createFormData("audio", name, body),
            context.toRequestBody(text), hotwords.toRequestBody(text), diarization.toString().toRequestBody(text)).also(::validate)
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
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onTicket: (RecordingUploadTicket) -> Unit = {},
        diarization: Boolean = false,
    ): Result<RecordingUploadState> = scoped(viewer) {
        uuid(key)
        require(directUploadEnabled(config))
        require(size in 1..config.directMaxBytes)
        require(name.substringAfterLast('.', "").lowercase() in config.extensions)
        require(context.length <= 400 && hotwords.length <= 4000)
        require(contentType.isNotBlank() && contentType.length <= 128)
        val signed = ticket ?: api.presign(
            RecordingUploadPresign(key, name, size, contentType, context, hotwords, diarization)
        ).also {
            require(it.storageName.isNotBlank() && it.uploadUrl.startsWith("https://"))
            require(it.headers["Content-Type"] == contentType)
        }
        onTicket(signed)
        val transferContext = currentCoroutineContext()
        if (!signed.uploaded) {
            if (!requireNotNull(storage).putWithProgress(signed.uploadUrl, signed.headers, size, open) { sent ->
                if (!transferContext.isActive || currentViewer() != viewer) throw IOException("Upload stopped")
                onProgress(sent, size)
            }) {
                throw IOException("Object storage refused the upload")
            }
            signed.uploaded = true
        }
        transferContext.ensureActive()
        onProgress(size, size)
        require(currentViewer() == viewer)
        api.complete(
            RecordingUploadComplete(key, name, size, contentType, signed.storageName, context, hotwords, diarization)
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

    /**
     * Import by resumable chunked upload: open, send parts, then adopt.
     *
     * [resumeFrom] is a session id the caller remembered. It is a hint, not a
     * source of truth: if the server no longer has it, `begin` reopens the same
     * intent, and either way the list of finished parts comes from storage.
     *
     * [onProgress] reports bytes genuinely stored, so a resumed upload starts
     * from what already landed rather than from zero.
     */
    suspend fun uploadChunked(request: ChunkedUploadRequest): Result<RecordingUploadState> =
        scoped(request.viewer) {
        val viewer = request.viewer
        val key = request.key
        val name = request.name
        val size = request.size
        val config = request.config
        val context = request.context
        val hotwords = request.hotwords
        val contentType = request.contentType
        val openAt = request.openAt
        val onProgress = request.onProgress
        val cancelled = request.cancelled
        uuid(key)
        val parts = partStorage ?: error("Chunked upload is not configured")
        require(config.directUploadAvailable && config.directMaxBytes > 0)
        require(size in 1..config.directMaxBytes)
        require(name.substringAfterLast('.', "").lowercase() in config.extensions)
        require(contentType.isNotBlank() && contentType.length <= 128)
        require(context.length <= 400 && hotwords.length <= 4000)

        val plan = request.resumeFrom
            ?.let { runCatching { api.multipartResume(it) }.getOrNull() }
            ?: api.multipartBegin(
                RecordingUploadBegin(key, name, size, contentType, context, hotwords, request.diarization)
            )
        plan.job?.let { return@scoped it.also(::validate) }
        val sessionId = plan.sessionId
        requirePlan(plan, size)
        if (plan.completionPending) {
            if (cancelled()) throw RecordingUploadCancelled()
            return@scoped api.multipartComplete(sessionId, RecordingUploadFinish(emptyList())).also {
                validate(it)
                onProgress(size, size)
            }
        }

        val held = plan.uploaded.associateBy { it.partNumber }.toMutableMap()
        var carried = plan.uploadedBytes
        onProgress(carried, size)

        val missing = (1..plan.partCount).filter { it !in held }
        // Signing is batched: one request per part would not fit the endpoint's
        // request budget on a six-gigabyte file.
        for (batch in missing.chunked(SIGN_BATCH)) {
            if (cancelled()) throw RecordingUploadCancelled()
            val signed = api.multipartSign(sessionId, RecordingUploadSign(batch))
            for (part in signed.parts) {
                if (cancelled()) throw RecordingUploadCancelled()
                require(part.partNumber in batch && part.url.startsWith("https://"))
                val offset = (part.partNumber - 1).toLong() * plan.partSize
                val length = minOf(plan.partSize, size - offset)
                val etag = parts.putPart(
                    url = part.url,
                    size = length,
                    open = { openAt(offset, length) },
                    onProgress = { sent -> onProgress(carried + sent, size) },
                    cancel = cancelled,
                ) ?: run {
                    // A part that returned nothing because the reader stopped is a
                    // cancel, not a lost response. Reporting it as the latter would
                    // tell them the file may already have been received and offer a
                    // retry of the very thing they just stopped.
                    if (cancelled()) throw RecordingUploadCancelled()
                    throw IOException("Part ${part.partNumber} was not stored")
                }
                held[part.partNumber] = RecordingUploadHeldPart(part.partNumber, etag, length)
                carried += length
                onProgress(carried, size)
            }
        }
        if (cancelled()) throw RecordingUploadCancelled()
        require(held.size == plan.partCount)
        api.multipartComplete(
            sessionId,
            RecordingUploadFinish(
                held.values.sortedBy { it.partNumber }
                    .map { RecordingUploadPartTag(it.partNumber, it.etag) }
            ),
        ).also(::validate)
    }

    private fun requirePlan(plan: RecordingUploadPlan, size: Long) {
        uuid(plan.sessionId)
        require(plan.size == size && plan.partSize > 0)
        require(plan.partCount == ((size + plan.partSize - 1) / plan.partSize).toInt())
        plan.uploaded.forEach { require(it.partNumber in 1..plan.partCount && it.etag.isNotBlank()) }
    }

    /** Tell the server to drop an upload in progress; incomplete parts are billed. */
    suspend fun abortChunked(viewer: String, sessionId: String): Result<Unit> = scoped(viewer) {
        uuid(sessionId)
        api.multipartAbort(sessionId)
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

/** Explicit copy into an upload; case-sensitive de-duplication, never truncate overflow. */
fun mergePersonalHotwords(current: String, saved: List<String>): String {
    val words = (current.split(Regex("[\\r\\n\\u000b\\u000c\\u001c-\\u001e\\u0085\\u2028\\u2029]")) + saved)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val text = words.joinToString("\n")
    require(words.size <= 100 && words.all { it.codePointCount(0, it.length) <= 40 } && text.codePointCount(0, text.length) <= 4000)
    return text
}
