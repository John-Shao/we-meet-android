package com.we.meet.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.we.meet.WeMeetApp
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "DeviceTimezoneReporter"

/**
 * Reports this device's IANA timezone to the backend so 免打扰时段 is evaluated in
 * the right wall clock.
 *
 * Why this exists: the server interprets quiet hours in `User.timezone`
 * (`core.services.push_send.quiet_user_ids`), whose default is the backend's
 * TIME_ZONE — **UTC** — and the only client that ever wrote it was the web app
 * (browser zone, via `useSyncUserPreferencesWithBackend`). Quiet hours, though,
 * only matter on the App: web has no push channel. So an App-only user who had
 * never signed in through a browser kept UTC, and a 22:00–08:00 window actually
 * silenced 06:00–16:00 Beijing time — daytime muted, small hours wide open. The
 * starred-contact bypass hangs off the same check, so it was off by the same
 * amount.
 *
 * Triggered from the same points as [PushTokenUploader.uploadIfPossible]
 * (app start + both login-success paths), and no-ops until a Bearer exists.
 *
 * Cost: the last reported zone is cached on disk, so a normal launch spends
 * **zero** requests. Only a first run, a device-zone change (travel / DST rule
 * change) or a reinstall costs one `users/me/` + one PATCH. The notification
 * screen additionally re-checks against the authoritative value it already gets
 * back from `push/preferences/` — that path costs nothing and self-heals the
 * case where a web sign-in overwrote the zone with the browser's.
 */
object DeviceTimezoneReporter {

    private const val PREFS_NAME = "device_timezone"
    private const val KEY_REPORTED = "reported_zone"

    private var app: WeMeetApp? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards against several triggers firing the same report concurrently. */
    @Volatile
    private var inFlight = false

    /** Called once from [WeMeetApp.onCreate]. */
    fun init(application: WeMeetApp) {
        app = application
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Report the device zone if it looks unreported; otherwise no-op. Safe to
     * call from any thread, any number of times.
     */
    fun reportIfNeeded() {
        val application = app ?: return
        if (!application.tokenStore.isLoggedIn()) return
        val device = ZoneId.systemDefault().id
        // Cheap path: we already told the server this zone — trust it and skip
        // the round-trip entirely.
        if (prefs(application).getString(KEY_REPORTED, null) == device) return
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                application.profileRepository.syncDeviceTimezone()
                    .onSuccess { reported ->
                        // Mark it reported either way: null means the server
                        // already agreed with us, which is just as final.
                        prefs(application).edit().putString(KEY_REPORTED, device).apply()
                        if (reported != null) Log.i(TAG, "device timezone reported: $reported")
                    }
                    .onFailure {
                        // Silent retry policy, same as PushTokenUploader: the
                        // next trigger (login / app restart) tries again.
                        Log.w(TAG, "device timezone report failed", it)
                    }
            } finally {
                inFlight = false
            }
        }
    }

    /**
     * Forget the cached zone on sign-out: `User.timezone` is per-account, so the
     * next account to log in on this device must report for itself rather than
     * inherit "already reported" from the previous one.
     */
    fun forgetReported() {
        val application = app ?: return
        prefs(application).edit().remove(KEY_REPORTED).apply()
    }
}
