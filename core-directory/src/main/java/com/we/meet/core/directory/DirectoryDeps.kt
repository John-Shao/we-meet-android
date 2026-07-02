package com.we.meet.core.directory

import okhttp3.OkHttpClient

/**
 * Host-contract this module depends on — same shape as the feature modules'
 * deps interfaces. The host app (WeMeetApp) implements it; property signatures
 * intentionally match ImDeps/AssistantDeps so one override satisfies all.
 */
interface DirectoryDeps {

    /** Host's authenticated OkHttp (AuthInterceptor + 401 silent refresh already wired). */
    val authedOkHttp: OkHttpClient

    /** Host backend base URL, e.g. `https://meet.we-meet.online`. */
    val baseUrl: String
}
