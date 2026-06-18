package com.we.meet.feature.im

import okhttp3.OkHttpClient

/**
 * Host-contract this feature module depends on.
 *
 * The host app (WeMeetApp) declares it implements [ImDeps] and supplies the three
 * fields below — the feature builds its own Retrofit + jusi-light-im Client on top
 * of these, so the feature doesn't re-implement Bearer auth or know about Keycloak.
 */
interface ImDeps {

    /** Host's authenticated OkHttp (AuthInterceptor + 401 silent refresh already wired). */
    val authedOkHttp: OkHttpClient

    /** Host backend base URL, e.g. `https://meet.we-meet.online`. Used to call /api/v1.0/im/token. */
    val baseUrl: String

    /**
     * jusi-light-im server origin (no trailing slash), e.g. `https://im.we-meet.online`.
     * Surfaced via BuildConfig.JUSI_IM_BASE_URL.
     */
    val jusiImBaseUrl: String
}
