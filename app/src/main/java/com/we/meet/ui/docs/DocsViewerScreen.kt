package com.we.meet.ui.docs

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * 云文档编辑画布(M3,设计文档 §4.6):独立轻量 WebView,直载 `?chrome=editor`
 * 的收敛编辑器，也用于评论定位和版本预览。
 * 有未保存正文时，系统返回与顶栏关闭都等待 Web 确认持久化成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsEditorScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val darkTheme = WeMeetTheme.isDark
    val webView =
        remember { createDocsWebView(context, darkTheme = darkTheme, deferInitialLoad = true) }
    // 评论锚定:编辑画布 URL 可带 `thread=<threadId>`,加载完成后让 docs 定位到该评论。
    LaunchedEffect(webView, url) { loadDocsEditorEntry(context, webView, url) }
    var loading by remember { mutableStateOf(true) }
    var everLoaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    // 标题使用 Web 自身组件，避免两套标题状态并发覆盖。
    val docId = remember(url) { com.we.meet.feature.docs.util.DocLinks.docIdFromUrl(url, com.we.meet.BuildConfig.WE_MEET_DOCS_URL) }
    var editorDirty by remember(docId) { mutableStateOf(false) }
    var showUnsavedDialog by remember(docId) { mutableStateOf(false) }
    var saveRequestId by remember(docId) { mutableStateOf<String?>(null) }
    var saveFailed by remember(docId) { mutableStateOf(false) }
    val currentOnClose by androidx.compose.runtime.rememberUpdatedState(onClose)
    DisposableEffect(webView) {
        val client = webView.webViewClient as? DocsWebViewClient
        client?.onLoadingChanged = { l ->
            loading = l
            if (l) error = false else everLoaded = true
        }
        client?.onMainFrameError = { error = true; loading = false }
        // 脏检查(设计文档 §4.6):docs 编辑器保存队列未同步时上报,宿主据此守卫返回。
        client?.onEditorDirty = { dirty -> editorDirty = dirty }
        client?.onEditorSaveResult = { requestId, success ->
            if (requestId == saveRequestId) {
                saveRequestId = null
                if (success) {
                    editorDirty = false
                    currentOnClose()
                } else {
                    saveFailed = true
                }
            }
        }
        onDispose {
            client?.onHistoryChanged = null
            client?.onLoadingChanged = null
            client?.onMainFrameError = null
            client?.onEditorDirty = null
            client?.onEditorSaveResult = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    // URL 中的评论/版本参数由 Web 在编辑器数据就绪后消费。
    val requestClose = {
        if (editorDirty) showUnsavedDialog = true else currentOnClose()
    }
    BackHandler { requestClose() }
    LaunchedEffect(saveRequestId) {
        if (saveRequestId != null) {
            kotlinx.coroutines.delay(15_000)
            saveRequestId = null
            saveFailed = true
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = stringResource(R.string.docs_editor_title),
                onClose = requestClose,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
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

    if (showUnsavedDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (saveRequestId == null) showUnsavedDialog = false },
            title = { Text(stringResource(R.string.docs_editor_unsaved_title)) },
            text = { Text(stringResource(if (saveFailed) R.string.docs_editor_save_failed else R.string.docs_editor_unsaved_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // 保存并退出:先让 docs 落库,再回退。
                        val requestId = java.util.UUID.randomUUID().toString()
                        saveFailed = false
                        saveRequestId = requestId
                        postToDocs(
                            webView,
                            org.json.JSONObject().put("type", "wemeet-save-now")
                                .put("docId", docId).put("requestId", requestId),
                        )
                    },
                    enabled = saveRequestId == null,
                ) { Text(stringResource(R.string.docs_editor_unsaved_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showUnsavedDialog = false },
                    enabled = saveRequestId == null,
                ) { Text(stringResource(R.string.docs_editor_unsaved_continue)) }
            },
        )
    }
}

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
