package com.we.meet.feature.docs

import coil.ImageLoader
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.feature.docs.data.DocsRepository

/**
 * Host-contract this module depends on — same shape as ImDeps/AssistantDeps.
 *
 * The host app (WeMeetApp) implements it; `authedOkHttp` / `baseUrl` come from
 * [DirectoryDeps] so one override satisfies all feature modules. The host also
 * owns the docs-session stack ([DocsRepository] → DocsSessionManager) because,
 * like every other repository, it must outlive individual screens.
 */
interface DocsDeps : DirectoryDeps {

    /** La Suite Docs origin (no trailing slash), e.g. `https://docs.we-meet.online`. */
    val docsBaseUrl: String

    /** Docs REST access, bootstrapped from the host's authenticated session. */
    val docsRepository: DocsRepository

    /** Coil loader wired to the docs session cookies — doc media needs them. */
    val docsMediaLoader: ImageLoader
}
