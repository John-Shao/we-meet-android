package com.we.meet.feature.docs.data.net

/** Storage contract lets account-boundary tests exercise the real HTTP stack. */
interface DocsCredentials {
    var sessionId: String?
    var csrfToken: String?
    var ownerKey: String?
    fun clear()
}
