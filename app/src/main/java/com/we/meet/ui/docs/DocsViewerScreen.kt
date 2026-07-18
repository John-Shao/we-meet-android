package com.we.meet.ui.docs

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
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
    val webView =
        remember { createDocsWebView(context, initialUrl = url, darkTheme = darkTheme) }
    var canGoBack by remember { mutableStateOf(false) }

    DisposableEffect(webView) {
        val client = webView.webViewClient as? DocsWebViewClient
        client?.onHistoryChanged = { canGoBack = webView.canGoBack() }
        onDispose {
            client?.onHistoryChanged = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.docs_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
