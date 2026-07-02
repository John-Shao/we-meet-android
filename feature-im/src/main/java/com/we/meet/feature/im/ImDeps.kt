package com.we.meet.feature.im

import com.we.meet.core.directory.DirectoryDeps

/**
 * Host-contract this feature module depends on.
 *
 * The host app (WeMeetApp) declares it implements [ImDeps] and supplies the
 * fields below — the feature builds its own Retrofit + jusi-light-im Client on top
 * of these, so the feature doesn't re-implement Bearer auth or know about Keycloak.
 *
 * Extends [DirectoryDeps] (`authedOkHttp` + `baseUrl`) so IM screens can host
 * the shared ContactPicker / directory data layer with the same deps object.
 */
interface ImDeps : DirectoryDeps {

    /**
     * jusi-light-im server origin (no trailing slash), e.g. `https://im.we-meet.online`.
     * Surfaced via BuildConfig.JUSI_IM_BASE_URL.
     */
    val jusiImBaseUrl: String
}
