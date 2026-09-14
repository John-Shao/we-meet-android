package com.we.meet.data

import androidx.compose.ui.platform.UriHandler
import com.we.meet.ui.records.RecordLink
import com.we.meet.ui.records.RecordUriHandler
import org.junit.Assert.*
import org.junit.Test

class RecordUriHandlerTest {
    private val base = "https://meet.we-meet.online"
    private val record = "141aefcd-e478-4061-992b-b6800fe52ca5"
    private val summary = "a77bf22e-d700-427c-8bc7-7120df8d8fa1"
    private val path = "/meeting/records/$record"
    private val native = mutableListOf<RecordLink>()
    private val browser = mutableListOf<String>()
    private fun handler(enabled: Boolean = true) = RecordUriHandler(base, enabled, { native += it },
        object : UriHandler { override fun openUri(uri: String) { browser += uri } })

    @Test fun assistantNotesAndExactSummaryButtonsNeverLaunchBrowser() {
        val handler = handler()
        handler.openUri("$base$path")
        handler.openUri("$base$path?summary=$summary")
        assertEquals(listOf(RecordLink(record), RecordLink(record, summary)), native)
        assertTrue(browser.isEmpty())
    }

    @Test fun unrelatedLinksRetainExistingHandler() {
        val urls = listOf("https://docs.we-meet.online/docs/example", "$base/meeting", "https://other.example$path")
        urls.forEach(handler()::openUri)
        assertEquals(urls, browser)
        assertTrue(native.isEmpty())
    }

    @Test fun invalidSummaryDoesNotSilentlyOpenLatestVersion() {
        val url = "$base$path?summary=invalid"
        handler().openUri(url)
        assertEquals(listOf(url), browser)
        assertTrue(native.isEmpty())
    }

    @Test fun disabledNativeFeatureKeepsWebFallback() {
        val url = "$base$path?summary=$summary"
        handler(false).openUri(url)
        assertEquals(listOf(url), browser)
        assertTrue(native.isEmpty())
    }
}
