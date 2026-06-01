package com.we.meet.feature.assistant

import okhttp3.OkHttpClient

/**
 * Everything the host app must provide for the assistant feature to run.
 *
 * Lets :feature-assistant reuse the host's *authenticated* networking
 * (Bearer token + silent refresh + 401 handling) instead of owning its own
 * auth / login / token storage. The module builds its own Retrofit + APIs
 * on top of [authedOkHttp] so it stays decoupled from the host's API layer.
 */
interface AssistantDeps {
    /** Host's authenticated OkHttp client (AuthInterceptor + token refresh). */
    val authedOkHttp: OkHttpClient

    /** Host backend base URL, e.g. ``https://meet.we-meet.online``. */
    val baseUrl: String
}
