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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import com.we.meet.WeMeetApp
import com.we.meet.data.api.DocsSessionRequest
import com.we.meet.design.R as DesignR
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
 * 登录态由 [docsEntryUrl] 引导:后端拿 App 自己的登录态(Bearer)去 Docs 换一张
 * 一次性票据,WebView 载着票据进站即得 Docs 会话 —— 与 WebView cookie 罐里有没有
 * Keycloak 会话无关。老路子([docsUrl] 的 OIDC authenticate 入口)保留作兜底:它
 * 赌的那份 KC 会话 cookie 不随 App 登录态存活,赌输时云文档 tab 会变成一张 Keycloak
 * 手机号登录页 —— 其它 tab 全都登着,唯独云文档要求再登一次。
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
     * docs 挂载后宣告自己支持哪些内嵌协议(`wemeet-embed-hello`)。宿主收到后回一条
     * `wemeet-host-hello` 宣告自己有什么 —— docs 据此才决定要不要收敛自带入口。
     *
     * **能力驱动而非版本驱动**:三端发布速度差两个数量级,按版本猜对方支持什么,
     * 迟早撞上「docs 把搜索收了、宿主却还没提供替代」的死角。
     */
    var onEmbedHello: (() -> Unit)? = null

    /** docs 里点搜索 / 按 Ctrl+K:它的自带搜索已收敛,请求宿主打开全局搜索。 */
    var onOpenSearch: (() -> Unit)? = null

    /** docs 左右面板的开合状态,驱动返回键的优先级(见 [DocsTabScreen])。 */
    var onPanelState: ((leftOpen: Boolean, rightOpen: Boolean) -> Unit)? = null

    /**
     * docs 左栏(移动端是抽屉)是否展开。
     *
     * 同 [isLoading]:状态记在 client 上而不只发回调。消息可能在用户还没点进云文档
     * tab 时就到了(WebView 是预加载的),DocsTabScreen 组合时要能拿到当前值。
     * docs 老镜像不发 panel-state → 恒为 false → 返回键行为与改动前一致。
     */
    var leftPanelOpen: Boolean = false
        private set

    internal fun updatePanelState(leftOpen: Boolean, rightOpen: Boolean) {
        leftPanelOpen = leftOpen
        onPanelState?.invoke(leftOpen, rightOpen)
    }

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
            // ⚠️ 未知 type 一律静默忽略 —— 新版 docs 会发老 App 不认识的消息,
            // 这里必须当没看见,否则每加一条协议就得先发 App。
            val type = o.optString("type")
            if (type !in HANDLED_EVENTS) return
            // webView.url 必须在主线程读(WebView 非线程安全);顺带在主线程做 host 校验。
            // host 校验对**所有** type 生效,不只分享那条。
            mainHandler.post {
                val host = Uri.parse(webView.url).host?.lowercase(Locale.ROOT)
                if (host == null || host != DOCS_HOST) {
                    Log.w(TAG, "[bridge] postEvent from non-docs page dropped: $host")
                    return@post
                }
                when (type) {
                    "wemeet-share-doc" -> {
                        val docId = o.optString("docId")
                        val title = o.optString("title")
                        val url = o.optString("url")
                        if (docId.isBlank() || url.isBlank()) return@post
                        client.onShareDoc?.invoke(docId, title, url)
                    }
                    "wemeet-embed-hello" -> client.onEmbedHello?.invoke()
                    "wemeet-open-search" -> client.onOpenSearch?.invoke()
                    "wemeet-panel-state" -> client.updatePanelState(
                        o.optBoolean("leftPanelOpen"),
                        o.optBoolean("rightPanelOpen"),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "[bridge] malformed postEvent payload", it) }
    }

    private companion object {
        val HANDLED_EVENTS = setOf(
            "wemeet-share-doc",
            "wemeet-embed-hello",
            "wemeet-open-search",
            "wemeet-panel-state",
        )
    }
}

/**
 * 向 WebView 里的 docs 发一条消息。
 *
 * ⚠️ payload 一律用 [JSONObject] 拼再整体注入,**绝不**把变量插进 JS 源码字符串 ——
 * 那等于把任意值当代码执行。必须在主线程调用(WebView 非线程安全)。
 *
 * ⚠️ 反方向(docs→原生)永远只走 `WeMeetHost.postEvent(String)` 这一个方法,
 * **不要给 WeMeetHost 加新方法**:新版 docs 若调一个老 App 上不存在的方法,是
 * TypeError → 白屏级故障。扩展一律加 type。
 */
internal fun postToDocs(webView: WebView, payload: JSONObject) {
    webView.evaluateJavascript("window.postMessage($payload,'*')", null)
}

@SuppressLint("SetJavaScriptEnabled")
fun createDocsWebView(
    context: Context,
    initialUrl: String? = null,
    /** UA 主题标记(docs embedderTheme 解析初始深浅);调用方传当前主题。 */
    darkTheme: Boolean = false,
    /**
     * true = 这里不做初始加载,由调用方随后用 [loadDocsEntry] 驱动。进站 URL 现在要
     * 先向后端换一张登录票据(suspend),构造函数里做不了;两个调用方都走这条路,
     * 直接在构造时 load 只会先闪一下没有登录态的老入口。
     */
    deferInitialLoad: Boolean = false,
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
        // (docs 会话 cookie 由 CookieManager 全局共享,登录态自动带上)。
        if (!deferInitialLoad) loadUrl(initialUrl ?: docsUrl())
    }

/**
 * 云文档进站 URL:优先「票据引导」,失败退回 [fallback]。
 *
 * we-meet 后端用调用者自己的登录态(App 的 Bearer)去 Docs 换一张 60 秒、单次使用
 * 的票据,拼成可直接加载的 URL;WebView 载过去就拿到 Docs 会话,**全程不碰
 * Keycloak**。后端未接 Docs / 不可达 / 调用失败时返回 [fallback](老的 authenticate
 * 入口或文档深链本身),行为与改动前一致。
 *
 * @param next Docs 站内落地路径(可带 query),票据校验通过后 302 过去。
 */
internal suspend fun docsEntryUrl(context: Context, next: String, fallback: String): String {
    val app = context.applicationContext as? WeMeetApp ?: return fallback
    val url = runCatching { app.apiClient.docsApi.createSession(DocsSessionRequest(next)).url }
        .onFailure { Log.w(TAG, "[bootstrap] docs session ticket failed", it) }
        .getOrNull()
    return url?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * 云文档 tab 的进站加载:票据优先,拿不到退回 authenticate 入口。
 *
 * WebView.loadUrl 必须在主线程。不要依赖网络库恢复 continuation 的线程:
 * 某些 Retrofit/OkHttp 路径会直接在响应线程恢复,设备上会被 WebView 的线程检查拦截。
 */
suspend fun loadDocsTabEntry(context: Context, webView: WebView) {
    // chrome=full:显式声明**不是**阅读态。docs 侧会据此清掉 sessionStorage 里的
    // 阅读态标记 —— 不这么做就得赌两个 WebView 实例的 sessionStorage 互相隔离,
    // 赌输的话打开一次文档查看器,常驻的云文档 tab 就永久丢了左栏开关。
    val next = "/?embed=1&chrome=full&lang=${Uri.encode(appLanguageTag())}"
    val entryUrl = docsEntryUrl(context, next = next, fallback = docsUrl())
    withContext(Dispatchers.Main.immediate) { webView.loadUrl(entryUrl) }
}

/**
 * 文档深链(搜索命中 / 聊天里的 doc-card)查看器的进站加载:同样票据优先,拿不到
 * 就直载深链本身 —— 那时靠云文档 tab 早前在 CookieManager 里留下的 docs 会话。
 *
 * 带 `chrome=none` 让 docs 进「阅读态」:这一屏已经有 App 自己的顶栏
 * ([DocsViewerScreen] 的 WeMeetTopBar),docs 再给一个左栏抽屉开关就是第三层导航壳。
 * docs 侧在模块求值时就把它落进 sessionStorage(见 useEmbedShell),所以活得过
 * 票据 302 与站内跳转;文档自身的分享 / 工具箱 / 右栏一个不少。
 */
suspend fun loadDocsDeepLinkEntry(context: Context, webView: WebView, url: String) {
    val next = docsRelativePathOrNull(url)?.let(::withReadingMode)
    val entryUrl = if (next == null) url else docsEntryUrl(context, next = next, fallback = url)
    withContext(Dispatchers.Main.immediate) { webView.loadUrl(entryUrl) }
}

/** 给站内相对路径追加 `chrome=none`(已有则不重复加)。 */
private fun withReadingMode(path: String): String = when {
    path.contains("chrome=none") -> path
    path.contains('?') -> "$path&chrome=none"
    else -> "$path?chrome=none"
}

/**
 * 把 Docs 站上的绝对 URL 折成站内相对路径(票据引导的 `next`);非 Docs 站返回 null。
 */
private fun docsRelativePathOrNull(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (!uri.host.equals(DOCS_HOST, ignoreCase = true)) return null
    val path = uri.path.orEmpty().ifEmpty { "/" }
    val query = uri.query
    return if (query.isNullOrEmpty()) path else "$path?$query"
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
    var leftPanelOpen by remember { mutableStateOf(docsClient?.leftPanelOpen ?: false) }

    DisposableEffect(webView) {
        val client = docsClient
        client?.onHistoryChanged = { canGoBack = webView.canGoBack() }
        client?.onLoadingChanged = { l ->
            loading = l
            if (l) error = false else everLoaded = true
        }
        client?.onMainFrameError = { error = true; loading = false }
        client?.onPanelState = { leftOpen, _ -> leftPanelOpen = leftOpen }
        onDispose {
            client?.onHistoryChanged = null
            client?.onLoadingChanged = null
            client?.onMainFrameError = null
            client?.onPanelState = null
        }
    }

    /**
     * 返回键三级优先级:先关抽屉 → 再退页内历史 → 都没有才放行(切走 tab)。
     *
     * 原本只有 `enabled = canGoBack`:docs 的左栏在手机上是覆盖全屏的抽屉,抽屉开着
     * 且没有页内历史时按返回会**直接切走 tab,抽屉还开着**,回来一看还是那个抽屉 ——
     * 系统返回键在这里的语义和用户预期正好相反。
     *
     * 抽屉状态由 docs 经 wemeet-panel-state 上报;老镜像不发 → 恒 false → 退回原来的
     * 两级逻辑,不需要两端同时上线。
     */
    BackHandler(enabled = leftPanelOpen || canGoBack) {
        when {
            leftPanelOpen -> postToDocs(
                webView,
                JSONObject()
                    .put("type", "wemeet-ui-command")
                    .put("command", "close-left-panel"),
            )
            canGoBack -> webView.goBack()
        }
    }

    // MainActivity is edge-to-edge, so adjustResize exposes the keyboard as an
    // IME inset instead of shrinking this Compose-hosted WebView for us. Resize
    // the host explicitly; Chromium can then scroll the focused editor block
    // into the visible viewport instead of leaving the caret under the keyboard.
    Box(modifier = Modifier.fillMaxSize().imePadding()) {
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
                    Text(stringResource(DesignR.string.common_retry))
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
