package com.we.meet.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.we.meet.BuildConfig
import com.we.meet.WeMeetApp
import com.we.meet.data.api.PushTokenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "PushTokenUploader"

/**
 * Uploads the Getui cid to the we-meet backend (`POST /api/v1.0/push/tokens/`)
 * so it can route offline IM pushes to this device.
 *
 * The upload needs two things that arrive in either order:
 *  1. a cid (Getui hands it to [WeMeetGtIntentService.onReceiveClientId],
 *     which persists it here), and
 *  2. a logged-in user (Bearer token in TokenStore — the endpoint is authed).
 *
 * So [uploadIfPossible] is called from BOTH triggers — the cid callback and
 * the login-success path (AuthRepository.verifyOtp) — and simply no-ops until
 * both preconditions hold. Failures are silent: the next trigger (next login,
 * next cid callback, typically next app start) retries naturally.
 */
object PushTokenUploader {

    private const val PREFS_NAME = "getui_push"
    private const val KEY_CID = "cid"

    private var app: WeMeetApp? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last cid we successfully reported, to skip redundant uploads in-process. */
    @Volatile
    private var lastUploadedCid: String? = null

    /** Called once from [WeMeetApp.onCreate]. */
    fun init(application: WeMeetApp) {
        app = application
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist the cid Getui assigned to this install, then try to upload. */
    fun onNewCid(context: Context, cid: String) {
        if (cid.isBlank()) return
        prefs(context).edit().putString(KEY_CID, cid).apply()
        uploadIfPossible()
    }

    fun storedCid(context: Context): String? = prefs(context).getString(KEY_CID, null)

    /**
     * Upload the cid if we have one AND the user is logged in; otherwise no-op.
     * Safe to call from any thread, any number of times.
     */
    fun uploadIfPossible() {
        val application = app ?: return
        val cid = storedCid(application) ?: return
        if (!application.tokenStore.isLoggedIn()) return
        if (cid == lastUploadedCid) return
        scope.launch {
            runCatching {
                val resp = application.apiClient.pushApi.registerToken(
                    PushTokenRequest(
                        cid = cid,
                        platform = "android",
                        app_version = BuildConfig.VERSION_NAME,
                    )
                )
                if (resp.isSuccessful) {
                    lastUploadedCid = cid
                    Log.i(TAG, "push token registered")
                } else {
                    Log.w(TAG, "push token upload rejected: HTTP ${resp.code()}")
                }
            }.onFailure {
                // Silent retry policy: nothing scheduled here — the next
                // trigger (login / cid callback / app restart) tries again.
                Log.w(TAG, "push token upload failed", it)
            }
        }
    }

    /**
     * Best-effort unregister on sign-out. Fired BEFORE TokenStore.clear() but
     * asynchronously, so it races the clear — if it loses, the request goes
     * out unauthenticated and the row simply stays. That's acceptable: the
     * backend re-binds a cid to whichever user registers it next
     * (update_or_create on account switch), so a stale row never pushes to
     * the wrong account.
     */
    fun unregisterQuietly() {
        val application = app ?: return
        val cid = storedCid(application) ?: return
        lastUploadedCid = null
        scope.launch {
            runCatching {
                application.apiClient.pushApi.unregisterToken(
                    com.we.meet.data.api.PushTokenDeleteRequest(cid = cid)
                )
            }.onFailure { Log.w(TAG, "push token unregister failed (best-effort)", it) }
        }
    }
}
