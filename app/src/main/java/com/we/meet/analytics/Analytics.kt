package com.we.meet.analytics

import android.content.Context
import android.util.Log
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.we.meet.BuildConfig

/**
 * Thin facade around the PostHog SDK. All call sites should go through
 * here so the SDK can stay optional — when [BuildConfig.WE_MEET_POSTHOG_KEY]
 * is blank the singleton degrades to no-ops and we never even initialise
 * the SDK. That covers the current aliyun-prod deployment (no analytics
 * key) and local dev builds.
 *
 * Event names line up with the Web client (`useAnalytics.ts` +
 * `posthog.capture(...)` call sites in features/recording etc.) so a
 * single PostHog project can dashboard both surfaces.
 */
object Analytics {

    private const val TAG = "Analytics"

    @Volatile
    private var initialized = false

    /**
     * Boot the SDK once at app start. No-op when the build wasn't given
     * a PostHog key — every other entry point also checks [initialized]
     * so capture / identify can be called freely without guarding.
     */
    fun init(context: Context) {
        if (initialized) return
        val key = BuildConfig.WE_MEET_POSTHOG_KEY
        if (key.isBlank()) {
            Log.d(TAG, "PostHog disabled (no key configured)")
            return
        }
        val config = PostHogAndroidConfig(apiKey = key, host = BuildConfig.WE_MEET_POSTHOG_HOST)
            .apply {
                // Mirror Web: capture richer user properties on identify.
                // captureScreenViews=true gives auto $screen events alongside
                // the explicit ones we capture below, matching Web's $pageview.
                captureScreenViews = true
                captureDeepLinks = true
                captureApplicationLifecycleEvents = true
            }
        PostHogAndroid.setup(context.applicationContext, config)
        initialized = true
        Log.d(TAG, "PostHog initialised")
    }

    /** Tag the current device with the signed-in user's id + email. */
    fun identify(userId: String, email: String? = null) {
        if (!initialized) return
        com.posthog.PostHog.identify(
            distinctId = userId,
            userProperties = mapOf(
                "email" to (email ?: ""),
                "platform" to "android",
            ),
        )
    }

    /** Clear the identified user (called on sign-out / deregister). */
    fun reset() {
        if (!initialized) return
        com.posthog.PostHog.reset()
    }

    /**
     * One-shot event. Properties default to empty — pass only fields that
     * are meaningful for the dashboard slice we want; nameless events
     * (no properties) are valid and surface as plain counters.
     */
    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        if (!initialized) return
        com.posthog.PostHog.capture(event = event, properties = properties)
    }

    // ── Event constants ─────────────────────────────────────────────────
    //
    // Keep these aligned with Web. New events go HERE first, then where
    // they're fired, so the dashboard owner has one place to audit names.

    const val EVENT_LOGIN = "login"
    const val EVENT_CREATE_MEETING = "create-meeting"
    const val EVENT_JOIN_MEETING = "join-meeting"
    const val EVENT_END_MEETING = "end-meeting"
    const val EVENT_RECORDING_START = "recording-start"
    const val EVENT_SUBTITLE_START = "subtitle-start"
    const val EVENT_ROOM_AI_QUERY = "room-ai-query"
}

/** Marker interface kept so future :feature-* modules can inject capture-only
 *  shims if they don't depend on the app module directly. */
@Suppress("unused")
private interface AnalyticsIntegrationHint : PostHogIntegration
