package com.we.meet.feature.docs.data.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted docs session credentials: the `docs_sessionid` and `csrftoken`
 * cookie values of the native docs stack.
 *
 * They are session cookies of the docs server (Django session + CSRF), i.e.
 * bearer-equivalent credentials for the signed-in user — hence the same
 * encrypted storage as the app's [com.we.meet.data.auth.TokenStore], never
 * plain SharedPreferences.
 */
class DocsSessionStore(context: Context) {

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

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    var csrfToken: String?
        get() = prefs.getString(KEY_CSRF_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CSRF_TOKEN, value).apply()

    fun clear() = prefs.edit().remove(KEY_SESSION_ID).remove(KEY_CSRF_TOKEN).apply()

    private companion object {
        const val FILE_NAME = "docs_session_prefs"
        const val KEY_SESSION_ID = "docs_sessionid"
        const val KEY_CSRF_TOKEN = "csrftoken"
    }
}
