package com.we.meet.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent, encrypted storage for the user's auth tokens.
 *
 * Uses [EncryptedSharedPreferences] so that the access token never lands on
 * disk in plaintext.  Backed by AndroidKeystore via the [MasterKey].
 *
 * MVP scope: we store the access token, refresh token, and a "phone" hint we
 * can show on the home screen.  We do NOT yet implement automatic refresh —
 * if the access token expires the user simply re-logs in.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var phone: String?
        get() = prefs.getString(KEY_PHONE, null)
        set(value) = prefs.edit().putString(KEY_PHONE, value).apply()

    var nickname: String?
        get() = prefs.getString(KEY_NICKNAME, null)
        set(value) = prefs.edit().putString(KEY_NICKNAME, value).apply()

    /** Personal bio synced from meet-backend (`/users/me/`), capped at 100 chars server-side. */
    var intro: String?
        get() = prefs.getString(KEY_INTRO, null)
        set(value) = prefs.edit().putString(KEY_INTRO, value).apply()

    /** Public avatar URL stored in object storage; empty string when the user has no avatar yet. */
    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR_URL, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_URL, value).apply()

    /** Public profile cover image URL; empty string when not set. */
    var coverUrl: String?
        get() = prefs.getString(KEY_COVER_URL, null)
        set(value) = prefs.edit().putString(KEY_COVER_URL, value).apply()

    /** meet-backend user UUID (needed for PATCH /users/{id}/). */
    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    fun isLoggedIn(): Boolean = !accessToken.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "jusi_meet_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_PHONE = "phone"
        const val KEY_NICKNAME = "nickname"
        const val KEY_INTRO = "intro"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_COVER_URL = "cover_url"
        const val KEY_USER_ID = "user_id"
    }
}
