package com.we.meet.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global, process-wide flag for "the backend told us our Bearer token is
 * no longer valid". Set by [SessionExpiredInterceptor] the moment any
 * authed request comes back 401; observed by [com.we.meet.ui.nav.AppNav]
 * to pop a modal dialog prompting the user to sign in again.
 *
 * Kept as a Kotlin `object` (not a member of [com.we.meet.WeMeetApp])
 * because the OkHttp interceptor is constructed before — and has no
 * reference to — the navigation layer. A global `StateFlow` is the
 * simplest cross-layer bridge.
 *
 * Multiple concurrent 401s converging on [markExpired] are idempotent:
 * setting `value = true` repeatedly is a no-op for observers.
 */
object SessionState {
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    fun markExpired() {
        _expired.value = true
    }

    fun reset() {
        _expired.value = false
    }
}
