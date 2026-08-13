package com.we.meet

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.call.CallSeed
import com.we.meet.push.CallNotifier
import com.we.meet.overlay.ScreenShareOverlay
import com.we.meet.ui.nav.AppNav
import com.we.meet.ui.theme.WeMeetTheme

private const val TAG = "MainActivity"

/**
 * Backend `Room.generate_unique_slug` only ever emits 8-digit numeric
 * codes — the legacy 3-4-3 lowercase-letter slug generator
 * (`generate_room_slug`) still exists in utils.py but the model no
 * longer calls it. Anything else coming in via App Links is a
 * mismatch (e.g. `/feedback` is also length-9 and would pass the
 * manifest pathPattern) and we ignore it so the Activity falls through
 * to its normal start destination.
 */
private val DEEP_LINK_SLUG_REGEX = Regex("^[0-9]{8}$")

/**
 * Compose-visible flag for "Activity is in Picture-in-Picture mode right now".
 * RoomScreen reads this to render [com.we.meet.ui.room.PipLayout] instead
 * of the full toolbar/gallery when we're in the PiP window.
 */
val LocalIsInPipMode = compositionLocalOf { false }

class MainActivity : AppCompatActivity() {

    /**
     * `true` while the user is actually in a connected meeting. RoomScreen
     * flips this via a DisposableEffect. Used by [onUserLeaveHint] (pre-12
     * fallback) so we never PiP from Login / Home.
     */
    private var inMeeting: Boolean = false

    /**
     * `true` while the local user is publishing a screen-share track. We
     * suppress Picture-in-Picture entirely in this state: a PiP window
     * rendering the meeting UI would be captured back by MediaProjection,
     * producing the hall-of-mirrors recursion we already guard against in
     * the gallery tile. When this flag is on the user is expected to be on
     * their home screen or inside another app anyway.
     */
    private var screenSharing: Boolean = false

    private val pipModeState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            // Pull the user's theme preference once at composition time;
            // a state-collect keeps the theme reactive when the user
            // changes it from Settings without restarting the app.
            val app = applicationContext as WeMeetApp
            val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                com.we.meet.data.settings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.we.meet.data.settings.ThemeMode.LIGHT -> false
                com.we.meet.data.settings.ThemeMode.DARK -> true
            }
            CompositionLocalProvider(LocalIsInPipMode provides pipModeState.value) {
                WeMeetTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNav()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Some launchers send a fresh App Links VIEW intent while the Activity
        // is already alive (e.g. tap link from a notification). Re-parse so
        // AppNav's collector fires a second time.
        handleDeepLink(intent)
    }

    /**
     * Pull a meeting slug out of an incoming `https://meet.we-meet.online/<slug>`
     * intent (manifest-side intent-filter pre-filters by path length, but
     * the Android `pathPattern` syntax can't express `[0-9]{8}`, so we do
     * the final shape check here in code). On a match, push the slug onto
     * [WeMeetApp.pendingJoinSlug] for AppNav to consume; anything else is
     * silently ignored so the Activity falls through to its normal start
     * destination.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        // wemeet://im?cid=<conversation id> — IM offline-push notification tap.
        // Same stash-on-Application pattern as pendingJoinSlug; AppNav owns
        // the consumption side.
        if (uri.scheme == "wemeet" && uri.host == "im") {
            val cid = uri.getQueryParameter("cid")?.takeIf { it.isNotBlank() } ?: return
            (application as? WeMeetApp)?.pendingChatCid?.value = cid
            return
        }
        // wemeet://call?payload=<json> — P2 来电通知点击(厂商通道通知 or 本地
        // FSI 通知)。Seed the call machine directly — AppNav's state collector
        // pushes the incoming-call screen once composition catches up, which
        // also covers the cold-start ordering (StateFlow, not an event).
        if (uri.scheme == "wemeet" && uri.host == "call") {
            val payloadJson = uri.getQueryParameter("payload")?.takeIf { it.isNotBlank() } ?: return
            val app = application as? WeMeetApp ?: return
            if (!app.tokenStore.isLoggedIn()) return
            val seed = CallSeed.fromJson(payloadJson) ?: return
            ImSession.get(app).calls.seedIncoming(seed)
            CallNotifier.cancel(this, seed.callId)
            return
        }
        val segments = uri.pathSegments.orEmpty()
        if (segments.size >= 3 && segments[0] == "calendar" && segments[1] == "subscribe") {
            (application as? WeMeetApp)?.pendingCalendarShareToken?.value = segments[2]
            return
        }
        if (segments.firstOrNull() == "calendar" && uri.getQueryParameter("external") == "connected") {
            (application as? WeMeetApp)?.pendingExternalCalendar?.value = true
            return
        }
        val slug = uri.pathSegments?.firstOrNull()?.takeIf {
            DEEP_LINK_SLUG_REGEX.matches(it)
        } ?: return
        (application as? WeMeetApp)?.pendingJoinSlug?.value = slug
    }

    // Gate the screen-share floating bubble on Activity visibility. The
    // overlay only shows while we're backgrounded — when the user returns to
    // the meeting UI they already have the in-app stop controls.
    override fun onStart() {
        super.onStart()
        ScreenShareOverlay.setForeground(true)
        (application as? WeMeetApp)?.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        ScreenShareOverlay.setForeground(false)
        (application as? WeMeetApp)?.isForeground = false
    }

    /**
     * Called by RoomScreen when the user enters / leaves a connected meeting.
     * On Android 12+ this also drives the system's auto-enter-PiP behaviour:
     * while in a meeting, a home-gesture auto-pips; outside, the Activity
     * backgrounds normally.
     */
    fun setMeetingInProgress(active: Boolean) {
        inMeeting = active
        applyPipParams()
    }

    /**
     * Called by RoomScreen when the local user starts / stops a screen share.
     * While true, we override the meeting-in-progress auto-PiP behaviour and
     * keep PiP disabled so Home-gesture just backgrounds us to the desktop
     * (where the actual share happens) instead of spawning a tiny meeting
     * window that MediaProjection would re-capture recursively.
     */
    fun setScreenSharing(active: Boolean) {
        screenSharing = active
        applyPipParams()
    }

    /**
     * Single place that decides whether auto-enter-PiP is on. Auto-enter
     * only makes sense when we're in a meeting AND not screen-sharing.
     */
    private fun applyPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val autoEnter = inMeeting && !screenSharing
        runCatching { setPictureInPictureParams(buildPipParams(autoEnter)) }
            .onFailure { Log.w(TAG, "setPictureInPictureParams failed", it) }
    }

    // Pre-12 fallback: onUserLeaveHint fires on Home press. Android 12+ with
    // setAutoEnterEnabled handles the gesture path itself.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!inMeeting || screenSharing) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        runCatching { enterPictureInPictureMode(buildPipParams(autoEnter = false)) }
            .onFailure { Log.w(TAG, "enterPictureInPictureMode failed", it) }
    }

    /** Called by the in-meeting "缩小" toolbar button to collapse into PiP. */
    fun enterPipNow() {
        if (!inMeeting || screenSharing) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(buildPipParams(autoEnter = true)) }
            .onFailure { Log.w(TAG, "enterPictureInPictureMode failed", it) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeState.value = isInPictureInPictureMode
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }
}
