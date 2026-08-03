package com.we.meet.ui.docs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.ui.theme.Dimens
import com.we.meet.BuildConfig
import com.we.meet.R
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
internal class DocsWebViewClient : WebViewClient() {

    /** Set by [DocsTabScreen] while it is on screen, to drive the back handler. */
    var onHistoryChanged: (() -> Unit)? = null

    /** UI-only hooks (don't affect navigation): drive a loading spinner and a
     *  main-frame error state so a failed load shows a retry instead of a
     *  blank page. */
    var onLoadingChanged: ((Boolean) -> Unit)? = null
    var onMainFrameError: (() -> Unit)? = null

    /**
     * 分享云文档到聊天(入口 B):docs 里点「分享到聊天」→ [DocsHostBridge] 收到
     * postEvent → 这里转发。Set by [DocsTabScreen] while it is on screen.
     */
    var onShareDoc: ((docId: String, title: String, url: String) -> Unit)? = null

    /**
     * 加载态同时记在 client 上,不只发回调:WebView 在 MainTabScreen 挂载时就开始
     * 预加载,若用户在加载**完成后**才首次点进云文档 tab,onPageFinished 早已错过,
     * DocsTabScreen 里 `loading` 的初值 true 永远翻不过来——转圈永久盖在已渲染好的
     * 页面上。组合时以这两个字段为初值即可消除该竞态。
     */
    var isLoading: Boolean = true
        private set
    var everLoaded: Boolean = false
        private set

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        onHistoryChanged?.invoke()
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        isLoading = true
        onLoadingChanged?.invoke(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        isLoading = false
        everLoaded = true
        onLoadingChanged?.invoke(false)
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
            onMainFrameError?.invoke()
        }
    }
}

/** docs 站点自身的 host,用于桥的调用方页面校验(见 [DocsHostBridge])。 */
private val DOCS_HOST: String? =
    Uri.parse(BuildConfig.WE_MEET_DOCS_URL).host?.lowercase(Locale.ROOT)

/**
 * 分享云文档到聊天(入口 B)的双向桥接:docs 前端(useIsEmbedded.sendToHost)在
 * 检测到 `window.WeMeetHost` 时调 `postEvent(JSON)`,取代 iframe 场景下的
 * `window.parent.postMessage`(这里是顶层 WebView,没有 parent frame)。
 *
 * 目前是这个 WebView 里第一个、也是唯一一个原生↔网页双向桥接(之前只有主题切换
 * 那种原生→网页单向注入)。JS interface 回调落在 WebView 的私有线程,必须先跳回
 * 主线程再碰 [client] 的回调(它最终会驱动 Compose state)。
 *
 * `addJavascriptInterface` 会把 `WeMeetHost` 暴露给这个 WebView 里加载的**所有**
 * 页面(docs / keycloak / meet 均留在 WebView 内),而合法调用方只有 docs。跳回
 * 主线程后先核对当前页面 host 是否 docs 自身([DOCS_HOST]):挡住非 docs 内部页
 * (如被 XSS 的 keycloak/meet 页)伪造分享、弹带假标题/链接的转发框做钓鱼。与 Web
 * 侧接收端 `DocsFrame.tsx` 的 `e.origin === docsOrigin` 校验对称。
 */
private class DocsHostBridge(
    private val webView: WebView,
    private val client: DocsWebViewClient,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postEvent(json: String) {
        runCatching {
            val o = JSONObject(json)
            if (o.optString("type") != "wemeet-share-doc") return
            val docId = o.optString("docId")
            val title = o.optString("title")
            val url = o.optString("url")
            if (docId.isBlank() || url.isBlank()) return
            // webView.url 必须在主线程读(WebView 非线程安全);顺带在主线程做 host 校验。
            mainHandler.post {
                val host = Uri.parse(webView.url).host?.lowercase(Locale.ROOT)
                if (host == null || host != DOCS_HOST) {
                    Log.w(TAG, "[bridge] postEvent from non-docs page dropped: $host")
                    return@post
                }
                client.onShareDoc?.invoke(docId, title, url)
            }
        }.onFailure { Log.w(TAG, "[bridge] malformed postEvent payload", it) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
fun createDocsWebView(
    context: Context,
    initialUrl: String? = null,
    /** UA 主题标记(docs embedderTheme 解析初始深浅);调用方传当前主题。 */
    darkTheme: Boolean = false,
): WebView =
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
        // 追加深浅标记:docs 的 embedderTheme() 从 UA 解析初始主题(?theme= 活不过
        // authenticate→returnTo→`/` 的重定向链,UA 挺得住)。运行时切换深浅另由
        // DocsTabScreen 注入 postMessage,无需重载。改动需与 we-meet-docs
        // useIsEmbedded.embedderTheme() 的解析同步。
        val themeTag = if (darkTheme) "theme=dark" else "theme=light"
        settings.userAgentString =
            "${settings.userAgentString} $EMBED_UA_MARKER; $themeTag"
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
        val client = DocsWebViewClient()
        webViewClient = client
        // 分享云文档到聊天(入口 B):见 DocsHostBridge 顶部注释。命名需与
        // we-meet-docs useIsEmbedded.sendToHost() 里检测的 `window.WeMeetHost` 一致。
        addJavascriptInterface(DocsHostBridge(this, client), "WeMeetHost")
        // 搜索统一 M2:文档命中查看器复用同一构造,直载目标文档深链
        // (KC session cookie 由 CookieManager 全局共享,登录态自动带上)。
        loadUrl(initialUrl ?: docsUrl())
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
    // session with zero user interaction, then bounces to the landing page.
    // Verified on device: /users/me/ 401 → 200, doc list renders.
    //
    // 注:docs authenticate 实际认的参数是 `next`(非此处 `returnTo`),所以此 URL
    // 的 ?embed=1&lang= 会被忽略、一律落 `/`(列表)。tab 要的正好是列表,embed/
    // theme 另靠 UA 兜底,功能正常,故此处**刻意保持 returnTo 不动**。Android 的
    // doc-card「查看文档」走 DocsViewerScreen 直载文档 URL(不经此端点),不受影响。
    // 若将来 Android 要经 authenticate 深链到某篇文档,再改成 next(见 Web DocsFrame)。
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
    val docsClient = remember(webView) { webView.webViewClient as? DocsWebViewClient }
    var canGoBack by remember { mutableStateOf(webView.canGoBack()) }
    // 初值取 client 当前真实状态,而非写死 true/false:首次进 tab 时预加载往往已经
    // 结束,回调不会再来,写死会让转圈永久盖住页面(见 DocsWebViewClient.isLoading)。
    var loading by remember { mutableStateOf(docsClient?.isLoading ?: true) }
    var everLoaded by remember { mutableStateOf(docsClient?.everLoaded ?: false) }
    var error by remember { mutableStateOf(false) }

    DisposableEffect(webView) {
        val client = docsClient
        client?.onHistoryChanged = { canGoBack = webView.canGoBack() }
        client?.onLoadingChanged = { l ->
            loading = l
            if (l) error = false else everLoaded = true
        }
        client?.onMainFrameError = { error = true; loading = false }
        onDispose {
            client?.onHistoryChanged = null
            client?.onLoadingChanged = null
            client?.onMainFrameError = null
        }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                // Re-entering the tab re-runs the factory with the same instance —
                // detach from the previous ComposeView parent before re-adding.
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
        )
        DocsLoadStateOverlay(
            // Only cover the page with a spinner on the FIRST load (the SSO
            // redirect chain that would otherwise show a blank page); later
            // in-app navigations let docs render progressively.
            loading = loading && !everLoaded,
            error = error,
            onRetry = { error = false; loading = true; webView.reload() },
        )
    }
}

/** Loading/error overlay shared by the docs tab and the standalone viewer. */
@Composable
internal fun DocsLoadStateOverlay(loading: Boolean, error: Boolean, onRetry: () -> Unit) {
    when {
        error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.docs_load_error),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = Dimens.SpaceS)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        }
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
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
