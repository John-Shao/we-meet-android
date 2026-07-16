package com.we.meet.ui.docs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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

/**
 * 云文档 tab (p3-docs-app.md D6): a WebView on La Suite Docs.
 *
 * Auth is invisible here by design — the in-WebView Keycloak login already
 * seeded the KC session cookie into the process-wide CookieManager, so Docs'
 * OIDC redirect chain completes silently. If the KC SSO session has expired
 * (realm max lifespan), the unified login page simply shows inline and the
 * user re-enters an OTP in place.
 *
 * `?embed=1` collapses Docs' own user area (logout/language) — persisted via
 * sessionStorage on the docs side because this WebView is TOP-LEVEL (not an
 * iframe) and the docs / → /home/ redirect drops the query. `?lang=` follows
 * the device locale.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createDocsWebView(context: Context): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        loadUrl(docsUrl())
    }

private fun docsUrl(): String {
    // i18next on the docs side lowercases + soft-matches ("zh-cn" → zh).
    val lang = Locale.getDefault().toLanguageTag().lowercase(Locale.ROOT)
    val base = BuildConfig.WE_MEET_DOCS_URL.trimEnd('/')
    return "$base/?embed=1&lang=${Uri.encode(lang)}"
}

/**
 * Renders a hoisted docs [webView]. The instance is created ONCE at
 * MainTabScreen level and survives tab switches — tab content is a plain
 * `when`-style recomposition, so holding it here would rebuild the WebView
 * (and replay the whole load + SSO redirect) on every switch.
 */
@Composable
fun DocsTabScreen(webView: WebView) {
    var canGoBack by remember { mutableStateOf(webView.canGoBack()) }

    DisposableEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean,
            ) {
                canGoBack = view?.canGoBack() == true
            }

            // Keep our own hosts (docs, keycloak, meet) in the WebView; kick
            // anything else (docs footer links etc.) out to the browser so the
            // user can't get trapped in a chrome-less external site.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme != "http" && scheme != "https") return true
                val host = uri.host.orEmpty()
                if (INTERNAL_HOSTS.any { host == it }) return false
                runCatching {
                    view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                return true
            }
        }
        onDispose { }
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

private val INTERNAL_HOSTS: List<String> = listOf(
    BuildConfig.WE_MEET_DOCS_URL,
    BuildConfig.WE_MEET_KEYCLOAK_URL,
    BuildConfig.WE_MEET_BASE_URL,
).mapNotNull { Uri.parse(it).host }
