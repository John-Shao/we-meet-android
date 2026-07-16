package com.we.meet.ui.docs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.BuildConfig
import java.util.Locale

private const val TAG = "WeMeetDocs"

/**
 * 云文档 tab (p3-docs-app.md D6): a WebView on La Suite Docs.
 *
 * Auth is invisible by design — the in-WebView Keycloak login seeded the KC
 * session cookie into the process-wide CookieManager, and [docsUrl] enters
 * through docs' OIDC authenticate endpoint, which trades that cookie for an
 * authenticated docs session with no user interaction.
 */
private class DocsWebViewClient : WebViewClient() {

    /** Set by [DocsTabScreen] while it is on screen, to drive the back handler. */
    var onHistoryChanged: (() -> Unit)? = null

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        onHistoryChanged?.invoke()
    }

    /**
     * Keep our own hosts (docs, keycloak, meet) in the WebView; kick genuinely
     * external sites out to the browser — but ONLY on a real tap.
     *
     * Returning true tells the WebView "I handled it, don't load". A REDIRECT
     * (docs / → /home/, the OIDC bounce) has no user gesture, so Chromium refuses
     * to launch an intent for it and the navigation is dropped — a blank page. So
     * no gesture ⇒ always load in place.
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme ?: return false
        val gesture = request.hasGesture()
        if (scheme != "http" && scheme != "https") {
            if (gesture) openExternally(view, uri)
            return true
        }
        if (isInternalHost(uri.host.orEmpty())) return false
        if (!gesture) {
            Log.d(TAG, "[nav] keep in webview (no gesture): $uri")
            return false
        }
        openExternally(view, uri)
        return true
    }

    private fun openExternally(view: WebView?, uri: Uri) {
        Log.d(TAG, "[nav] open externally: $uri")
        runCatching { view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    // Diagnostics: 401 = docs session not established; 5xx = docs down.
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        Log.w(TAG, "[http ${errorResponse?.statusCode}] ${request?.method} ${request?.url}")
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            Log.w(TAG, "[load error] ${error?.description} ${request.url}")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createDocsWebView(context: Context): WebView =
    WebView(context).apply {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Deliberately NOT useWideViewPort/loadWithOverviewMode: docs ships a
        // responsive viewport meta, and forcing the desktop viewport rendered the
        // page zoomed out to a few pixels. Pinch-zoom stays available.
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "[console] ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
        }
        // MUST be set BEFORE loadUrl, and cannot be deferred to DocsTabScreen:
        // a WebView with no WebViewClient hands every navigation to the system
        // (documented default). The docs load starts here — before the tab is ever
        // composed — so a late client meant docs' own redirects escaped the
        // WebView: silently dropped without a gesture (blank page), or popping
        // Chrome once a tab tap supplied one.
        webViewClient = DocsWebViewClient()
        loadUrl(docsUrl())
    }

private fun docsUrl(): String {
    // i18next on the docs side lowercases + soft-matches ("zh-cn" → zh).
    val lang = Locale.getDefault().toLanguageTag().lowercase(Locale.ROOT)
    val base = BuildConfig.WE_MEET_DOCS_URL.trimEnd('/')
    val target = "$base/?embed=1&lang=${Uri.encode(lang)}"
    // Enter through docs' OIDC authenticate endpoint instead of the bare root.
    // A fresh WebView has no docs session, and docs does NOT auto-login on `/` —
    // it renders its anonymous marketing landing ("Start Writing") and every
    // /users/me/ comes back 401, so the tab just sits on a spinner. Hitting
    // /authenticate/ trades the Keycloak session cookie (seeded by our in-WebView
    // login, shared via the process-wide CookieManager) for an authenticated docs
    // session with zero user interaction, then bounces to `returnTo`.
    // Verified on device: /users/me/ 401 → 200, doc list renders.
    return "$base/api/v1.0/authenticate/?returnTo=${Uri.encode(target)}"
}

/**
 * Renders a hoisted docs [webView]. The instance is created ONCE at
 * MainTabScreen level and survives tab switches — tab content is a plain
 * recomposition, so holding it here would rebuild the WebView (and replay the
 * whole load + SSO redirect) on every switch.
 */
@Composable
fun DocsTabScreen(webView: WebView) {
    var canGoBack by remember { mutableStateOf(webView.canGoBack()) }

    DisposableEffect(webView) {
        val client = webView.webViewClient as? DocsWebViewClient
        client?.onHistoryChanged = { canGoBack = webView.canGoBack() }
        onDispose { client?.onHistoryChanged = null }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            // Re-entering the tab re-runs the factory with the same instance —
            // detach from the previous ComposeView parent before re-adding.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
    )
}

/** Our own hosts: docs + keycloak + meet backend. */
private val INTERNAL_HOSTS: List<String> = listOf(
    BuildConfig.WE_MEET_DOCS_URL,
    BuildConfig.WE_MEET_KEYCLOAK_URL,
    BuildConfig.WE_MEET_BASE_URL,
).mapNotNull { Uri.parse(it).host?.lowercase(Locale.ROOT) }

/**
 * Registrable domains of the above (docs.we-meet.online → we-meet.online), so a
 * sibling subdomain we forgot to list still counts as ours.
 */
private val INTERNAL_DOMAINS: List<String> = INTERNAL_HOSTS
    .map { it.split(".").takeLast(2).joinToString(".") }
    .distinct()

private fun isInternalHost(rawHost: String): Boolean {
    val host = rawHost.lowercase(Locale.ROOT)
    if (host.isEmpty()) return false
    return INTERNAL_HOSTS.any { host == it } ||
        INTERNAL_DOMAINS.any { host == it || host.endsWith(".$it") }
}
