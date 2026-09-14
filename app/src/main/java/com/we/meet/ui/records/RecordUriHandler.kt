package com.we.meet.ui.records

import androidx.compose.ui.platform.UriHandler

/** Route trusted meeting selectors in-app, independently of Android App Link verification. */
internal class RecordUriHandler(
    private val baseUrl: String,
    private val enabled: Boolean,
    private val openRecord: (RecordLink) -> Unit,
    private val external: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        val record = if (enabled) RecordLinks.parse(uri, baseUrl) else null
        if (record != null) openRecord(record) else external.openUri(uri)
    }
}
