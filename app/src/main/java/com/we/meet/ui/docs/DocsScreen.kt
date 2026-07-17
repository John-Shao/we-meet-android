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
import androidx.appcompat.app.AppCompatDelegate
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
 * Appended to the docs WebView's User-Agent; docs' `useIsEmbedded` matches on it
 * to hide its own user area. Keep this string in sync with we-meet-docs
 * `src/frontend/apps/impress/src/hooks/useIsEmbedded.tsx`.
 */
private const val EMBED_UA_MARKER = "WeMeetApp/1.0 (embedded-docs)"

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
        // A programmatically-built WebView has no LayoutParams, so its host
        // measures it as WRAP_CONTENT — and Chromium then reports a CSS viewport
        // height of 0, making EVERY viewport unit (vh/dvh/svh/lvh) resolve to 0
        // while % still works. docs' left panel is `height: 100dvh`, so it
        // collapsed to 0 and (being overflow:hidden) rendered as an empty drawer:
        // tapping the panel toggle showed only the dim backdrop. Pin the size.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Tell docs it is embedded so it collapses its own user area (logout /
        // language / avatar) — meet already owns identity. The UA is the only
        // signal that survives here: this WebView is TOP-LEVEL, so docs'
        // `window.self !== window.top` check is false, and `?embed=1` is dropped
        // by the authenticate → returnTo → `/` redirect chain. The UA is not.
        // Appended (not replaced) so docs still sees a normal Chrome/Android UA.
        settings.userAgentString = "${settings.userAgentString} $EMBED_UA_MARKER"
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

/**
 * The language docs should render in: the app's own in-app language (我的 → 设置
 * → 语言, stored by [AppCompatDelegate.setApplicationLocales]), falling back to
 * the system locale when the user picked "follow system".
 *
 * NOT `Locale.getDefault()` alone: that is the *device* locale, which is exactly
 * what the WebView's `navigator`/Accept-Language already reports — using it would
 * make docs ignore an in-app language that differs from the device's.
 * Lowercased because docs' i18next runs with `lowerCaseLng` and soft-matches
 * ("zh-cn" → the `zh` bundle).
 */
private fun appLanguageTag(): String {
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val locale = if (!appLocales.isEmpty) appLocales[0] else null
    return (locale ?: Locale.getDefault()).toLanguageTag().lowercase(Locale.ROOT)
}

private fun docsUrl(): String {
    val lang = appLanguageTag()
    val base = BuildConfig.WE_MEET_DOCS_URL.trimEnd('/')
    // ?lang= is kept as belt-and-braces (it wins when it does survive, e.g. if the
    // redirect chain ever preserves the query); the cookie is what actually lands.
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
