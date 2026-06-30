package com.we.meet.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import com.we.meet.data.api.UserApi
import com.we.meet.data.api.dto.ConfirmProfileImageRequest
import com.we.meet.data.api.dto.UpdateIntroRequest
import com.we.meet.data.api.dto.UpdateNicknameRequest
import com.we.meet.data.api.dto.UploadUrlRequest
import com.we.meet.data.api.dto.UserDto
import com.we.meet.data.auth.AuthInterceptor
import com.we.meet.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages the user's meet-backend profile (intro / avatar / cover).
 *
 * Keycloak still owns firstName/lastName/email — see
 * ../jusi_meet_suite1.9/docs/mobile-integration-auth.md §5. This repository
 * only deals with the meet-backend-managed fields, documented in
 * ../jusi_meet_suite1.9/docs/mobile-integration-profile.md.
 */
class ProfileRepository(
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
    private val contentResolver: ContentResolver,
) {

    enum class Kind(val raw: String) {
        AVATAR("avatar"),
        COVER("cover"),
    }

    sealed class UploadError(message: String) : Exception(message) {
        object UnsupportedMime : UploadError("Unsupported MIME type")
        object TooLarge : UploadError("Image exceeds 2 MiB limit")
        object Empty : UploadError("Image is empty")
    }

    /** Refresh the locally-cached profile from the server. */
    suspend fun refreshProfile(): Result<UserDto> = runCatching {
        withContext(Dispatchers.IO) {
            val user = userApi.getMe()
            persistProfile(user)
            user
        }
    }

    /** Update the bio (`intro`). Server enforces the 100-char cap. */
    suspend fun updateIntro(intro: String): Result<UserDto> = runCatching {
        val userId = requireUserId()
        withContext(Dispatchers.IO) {
            val user = userApi.updateIntro(userId, UpdateIntroRequest(intro = intro))
            persistProfile(user)
            user
        }
    }

    /**
     * Update the display nickname (Keycloak firstName, surfaced as
     * UserDto.full_name on subsequent /users/me/ reads). Server proxies to
     * Keycloak Admin API to dodge the realm's user-profile validators.
     */
    suspend fun updateNickname(nickname: String): Result<UserDto> = runCatching {
        withContext(Dispatchers.IO) {
            val user = userApi.updateNickname(UpdateNicknameRequest(nickname = nickname))
            persistProfile(user)
            user
        }
    }

    /**
     * Deregister (anonymize) the user on the backend, then clear local
     * auth state so the caller can route back to the login screen.
     *
     * Crucially we DON'T clear the TokenStore until the server has acked
     * the request — if the request fails (network drop, server 5xx) the
     * user should land back on the same Profile screen still signed in,
     * not on Login with the data still live on the backend.
     */
    suspend fun deregister(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            userApi.deregister()
            tokenStore.clear()
        }
    }

    /**
     * Upload an avatar / cover image using the three-step presigned PUT
     * flow: request URL → PUT bytes → confirm.
     */
    suspend fun uploadProfileImage(kind: Kind, uri: Uri): Result<UserDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                val mime = resolveMime(uri)
                if (mime !in ALLOWED_MIME) throw UploadError.UnsupportedMime
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw UploadError.Empty
                uploadBytes(kind, bytes, mime)
            }
        }

    /**
     * Upload already-decoded image bytes (e.g. the avatar cropper's
     * [OUTPUT_SIZE]² JPEG render). Skips URI/MIME resolution since the caller
     * controls the encoding; everything else mirrors [uploadProfileImage].
     */
    suspend fun uploadProfileImageBytes(
        kind: Kind,
        bytes: ByteArray,
        mime: String,
    ): Result<UserDto> =
        runCatching {
            withContext(Dispatchers.IO) {
                uploadBytes(kind, bytes, mime)
            }
        }

    private suspend fun uploadBytes(kind: Kind, bytes: ByteArray, mime: String): UserDto {
        if (mime !in ALLOWED_MIME) throw UploadError.UnsupportedMime
        if (bytes.isEmpty()) throw UploadError.Empty
        if (bytes.size > MAX_SIZE_BYTES) throw UploadError.TooLarge

        val presigned = userApi.requestProfileUploadUrl(
            UploadUrlRequest(
                kind = kind.raw,
                content_type = mime,
                size = bytes.size.toLong(),
            )
        )

        val putRequest = Request.Builder()
            .url(presigned.upload_url)
            .put(bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .header(AuthInterceptor.NO_AUTH, "1")
            .apply {
                presigned.headers.forEach { (k, v) -> header(k, v) }
            }
            .build()

        okHttpClient.newCall(putRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Storage PUT failed: HTTP ${response.code}")
            }
        }

        val user = userApi.confirmProfileImage(
            ConfirmProfileImageRequest(
                kind = kind.raw,
                object_key = presigned.object_key,
            )
        )
        persistProfile(user)
        return user
    }

    private fun resolveMime(uri: Uri): String {
        contentResolver.getType(uri)?.let { return it }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: ""
    }

    private fun persistProfile(user: UserDto) {
        tokenStore.userId = user.id
        tokenStore.intro = user.intro
        tokenStore.avatarUrl = user.avatar_url
        tokenStore.coverUrl = user.cover_url
        if (!user.full_name.isNullOrBlank()) {
            tokenStore.nickname = user.full_name
        }
    }

    private fun requireUserId(): String =
        tokenStore.userId ?: throw IllegalStateException(
            "No cached user id; call refreshProfile() after login."
        )

    private companion object {
        const val MAX_SIZE_BYTES = 2L * 1024L * 1024L
        val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")
    }
}
