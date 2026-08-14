package com.we.meet.ui.docs

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.R
import com.we.meet.ui.theme.WeMeetTheme

/**
 * 搜索统一 M2:全局搜索「文档」命中的应用内查看器。
 *
 * 独立轻量 WebView(复用 [createDocsWebView] 全套配置:embed UA/允许域拦截/
 * cookie),直载文档深链;返回键先走 WebView 历史,退出即销毁——与常驻的
 * 云文档 Tab(共享 WebView)互不干扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsViewerScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val darkTheme = WeMeetTheme.isDark
    // deferInitialLoad:先向后端换一张 Docs 登录票据再进站(suspend,构造时做不了),
    // 这样即便这页先于云文档 tab 打开(cookie 罐里还没有 docs 会话)也能直接看到文档。
    val webView =
        remember { createDocsWebView(context, darkTheme = darkTheme, deferInitialLoad = true) }
    LaunchedEffect(webView, url) { loadDocsDeepLinkEntry(context, webView, url) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var everLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    DisposableEffect(webView) {
        val client = webView.webViewClient as? DocsWebViewClient
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
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.docs_viewer_title),
                onClose = onClose,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
            DocsLoadStateOverlay(
                loading = loading && !everLoaded,
                error = error,
                onRetry = { error = false; loading = true; webView.reload() },
            )
        }
    }
}
