package com.we.meet.feature.im.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Stable error codes the UI maps to i18n messages. */
class ChatUploadException(val code: Code, message: String? = null) : Exception(message ?: code.name) {
    enum class Code { InvalidType, TooLarge, UploadError }
}

/** Metadata carried in a file message body (JSON `{key,name,size}`). */
data class ChatFileMeta(val key: String, val name: String, val size: Long)

/**
 * Presigned-upload pipeline for chat media — port of the web client's
 * uploadChatImage.ts / uploadChatFile.ts.
 *
 * The PUT goes through a bare [OkHttpClient], NEVER the host's authed one:
 * AuthInterceptor would attach an Authorization header and break the presigned
 * S3 signature (mirror of the SDK's own OkHttp-isolation rule).
 */
internal class ChatUploadRepository(
    private val bridge: ImBridgeRepository,
    private val contentResolver: ContentResolver,
) {
    private val plainHttp = OkHttpClient()

    /**
     * Validate, (optionally) downscale + re-encode, upload; returns the stored
     * object_key — the caller sends it as the message body with content_type="image".
     * Gifs pass through unchanged (animation); others above 1600px/2MiB become
     * ≤1600px lossy webp.
     */
    suspend fun uploadImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        if (mime !in ALLOWED_IMAGE_TYPES) throw ChatUploadException(ChatUploadException.Code.InvalidType)

        val original = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ChatUploadException(ChatUploadException.Code.UploadError, "unreadable uri")

        val (bytes, contentType) = if (mime == "image/gif") {
            original to mime
        } else {
            prepareImage(original, mime)
        }
        if (bytes.size > IMAGE_MAX_BYTES) throw ChatUploadException(ChatUploadException.Code.TooLarge)

        val presigned = runCatching { bridge.imageUploadUrl(contentType, bytes.size.toLong()) }
            .getOrElse { throw ChatUploadException(ChatUploadException.Code.UploadError, it.message) }
        put(presigned, bytes, contentType)
        presigned.objectKey
    }

    /**
     * Centre-crop a selected image to a 600px JPEG, upload it to the private
     * avatar bucket, then confirm the object key with the owner-only endpoint.
     * Returns the short-lived URL from that authoritative confirmation.
     */
    suspend fun uploadGroupAvatar(cid: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        if (mime !in ALLOWED_GROUP_AVATAR_TYPES) {
            throw ChatUploadException(ChatUploadException.Code.InvalidType)
        }
        val original = contentResolver.openInputStream(uri)?.use {
            it.readBytesCapped(GROUP_AVATAR_SOURCE_MAX_BYTES)
        } ?: throw ChatUploadException(ChatUploadException.Code.UploadError, "unreadable uri")
        val bytes = prepareSquareJpeg(original)
        if (bytes.size > GROUP_AVATAR_MAX_BYTES) {
            throw ChatUploadException(ChatUploadException.Code.TooLarge)
        }

        val presigned = runCatching {
            bridge.groupAvatarUploadUrl(cid, GROUP_AVATAR_CONTENT_TYPE, bytes.size.toLong())
        }.getOrElse { throw ChatUploadException(ChatUploadException.Code.UploadError, it.message) }
        put(presigned, bytes, GROUP_AVATAR_CONTENT_TYPE)
        runCatching { bridge.updateGroupAvatar(cid, presigned.objectKey).avatarUrl }
            .getOrElse { throw ChatUploadException(ChatUploadException.Code.UploadError, it.message) }
    }

    /** Remove the custom image and restore the generated member mosaic. */
    suspend fun removeGroupAvatar(cid: String): String = withContext(Dispatchers.IO) {
        bridge.updateGroupAvatar(cid, "").avatarUrl
    }

    /** Upload a document; returns the metadata to JSON-encode as the message body. */
    suspend fun uploadFile(uri: Uri): ChatFileMeta = withContext(Dispatchers.IO) {
        val (name, size) = queryNameAndSize(uri)
        if (size > FILE_MAX_BYTES) throw ChatUploadException(ChatUploadException.Code.TooLarge)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"

        val bytes = contentResolver.openInputStream(uri)?.use {
            it.readBytesCapped(FILE_MAX_BYTES)
        }
            ?: throw ChatUploadException(ChatUploadException.Code.UploadError, "unreadable uri")

        val presigned = runCatching { bridge.fileUploadUrl(name, mime, bytes.size.toLong()) }
            .getOrElse { throw ChatUploadException(ChatUploadException.Code.UploadError, it.message) }
        put(presigned, bytes, mime)
        ChatFileMeta(key = presigned.objectKey, name = name, size = bytes.size.toLong())
    }

    /**
     * Upload a recorded voice clip (m4a/aac). Returns the object_key; the caller
     * sends it with content_type="voice", body `{key,duration}`.
     */
    suspend fun uploadVoice(file: java.io.File): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        if (bytes.size > AUDIO_MAX_BYTES) throw ChatUploadException(ChatUploadException.Code.TooLarge)
        val contentType = "audio/mp4"
        val presigned = runCatching { bridge.audioUploadUrl(contentType, bytes.size.toLong(), "voice.m4a") }
            .getOrElse { throw ChatUploadException(ChatUploadException.Code.UploadError, it.message) }
        put(presigned, bytes, contentType)
        presigned.objectKey
    }

    // ---- internals ----

    /** Downscale to ≤[MAX_EDGE]px longest edge + lossy webp when big; else pass through. */
    private fun prepareImage(original: ByteArray, mime: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) throw ChatUploadException(ChatUploadException.Code.InvalidType)
        if (longest <= MAX_EDGE && original.size <= RECODE_SIZE_THRESHOLD) {
            return original to mime
        }

        // Two-step decode: inSampleSize gets us within 2x cheaply, then an exact
        // scale lands on MAX_EDGE.
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(longest)
        }
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options)
            ?: throw ChatUploadException(ChatUploadException.Code.InvalidType)
        val decodedLongest = maxOf(decoded.width, decoded.height)
        val scaled = if (decodedLongest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / decodedLongest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION") // WEBP_LOSSY needs API 30; minSdk is 29.
        val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, WEBP_QUALITY, out)
        scaled.recycle()
        return out.toByteArray() to "image/webp"
    }

    private fun calculateInSampleSize(longest: Int): Int {
        var sample = 1
        var edge = longest
        while (edge / 2 >= MAX_EDGE) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    private fun prepareSquareJpeg(original: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        if (shortest <= 0) throw ChatUploadException(ChatUploadException.Code.InvalidType)

        var sample = 1
        while (shortest / (sample * 2) >= GROUP_AVATAR_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            original,
            0,
            original.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw ChatUploadException(ChatUploadException.Code.InvalidType)

        val side = minOf(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        if (cropped !== decoded) decoded.recycle()
        val scaled = if (side == GROUP_AVATAR_EDGE) cropped else {
            Bitmap.createScaledBitmap(cropped, GROUP_AVATAR_EDGE, GROUP_AVATAR_EDGE, true)
                .also { cropped.recycle() }
        }

        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, GROUP_AVATAR_JPEG_QUALITY, out)
        scaled.recycle()
        if (!ok) throw ChatUploadException(ChatUploadException.Code.UploadError, "jpeg encode failed")
        return out.toByteArray()
    }

    private fun put(presigned: UploadUrlResponse, bytes: ByteArray, contentType: String) {
        val builder = Request.Builder()
            .url(presigned.uploadUrl)
            .put(bytes.toRequestBody(contentType.toMediaTypeOrNull()))
        val headers = presigned.headers.ifEmpty { mapOf("Content-Type" to contentType) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        plainHttp.newCall(builder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                throw ChatUploadException(
                    ChatUploadException.Code.UploadError,
                    "storage PUT failed (${res.code})",
                )
            }
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                return (name ?: "file") to size
            }
        }
        return "file" to -1L
    }

    /** Read at most [maxBytes]. Some document providers report SIZE=-1, so
     * metadata validation alone cannot prevent a large attachment from being
     * loaded fully into memory. */
    private fun InputStream.readBytesCapped(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ChatUploadException(ChatUploadException.Code.TooLarge)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        const val IMAGE_MAX_BYTES = 10 * 1024 * 1024
        const val FILE_MAX_BYTES = 50 * 1024 * 1024
        const val AUDIO_MAX_BYTES = 20 * 1024 * 1024
        const val MAX_EDGE = 1600
        const val RECODE_SIZE_THRESHOLD = 2 * 1024 * 1024
        const val WEBP_QUALITY = 85
        val ALLOWED_GROUP_AVATAR_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        const val GROUP_AVATAR_SOURCE_MAX_BYTES = 10 * 1024 * 1024
        const val GROUP_AVATAR_MAX_BYTES = 2 * 1024 * 1024
        const val GROUP_AVATAR_EDGE = 600
        const val GROUP_AVATAR_JPEG_QUALITY = 90
        const val GROUP_AVATAR_CONTENT_TYPE = "image/jpeg"
    }
}
