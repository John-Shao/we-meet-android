package com.we.meet.ui.docs

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import com.we.meet.BuildConfig
import com.we.meet.feature.docs.util.DocLinks
import com.we.meet.feature.docs.util.EditorProtocol
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetErrorState
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

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
 * 每次退出都等待 Web 确认正文、标题及关联操作已完成；未握手前不开放画布。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsEditorScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val darkTheme = WeMeetTheme.isDark
    var currentUrl by rememberSaveable(url) { mutableStateOf(url) }
    var previouslyInteractive by rememberSaveable(url) { mutableStateOf(false) }
    var showReloadNotice by remember { mutableStateOf(previouslyInteractive) }
    val docId = DocLinks.docIdFromUrl(currentUrl, BuildConfig.WE_MEET_DOCS_URL).orEmpty()
    val webView = remember { createDocsWebView(context, darkTheme = darkTheme, deferInitialLoad = true) }
    var protocol by remember { mutableStateOf(EditorProtocol(docId, UUID.randomUUID().toString())) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var entryAttempt by remember { mutableStateOf(0) }
    var pendingNavigation by remember { mutableStateOf<String?>(null) }
    var leftPanel by remember { mutableStateOf(false) }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentDocId by rememberUpdatedState(docId)
    fun message(type: String, requestId: String? = protocol.requestId) = JSONObject()
        .put("type", type).put("docId", protocol.docId).put("editorInstanceId", protocol.instanceId)
        .put("requestId", requestId).put("protocolVersion", 2)
    fun retryEntry() {
        // Restart from the canonical editor URL with a fresh login ticket. Reloading
        // an intermediate login/redirect URL can reuse an already consumed ticket.
        loading = true
        error = false
        protocol = EditorProtocol(docId, UUID.randomUUID().toString())
        entryAttempt++
    }
    fun beginSave() {
        if (protocol.interactive) {
            protocol = protocol.save(UUID.randomUUID().toString())
            postToDocs(webView, message("wemeet-save-now"))
        }
    }
    fun navigateAfterSave(target: String): Boolean {
        if (!isDocsOrigin(Uri.parse(target))) return false
        if (!protocol.interactive && protocol.phase != EditorProtocol.Phase.SAVING) return false
        if (protocol.interactive) {
            pendingNavigation = target
            beginSave()
        }
        return true
    }
    val requestClose = {
        when {
            leftPanel -> postToDocs(webView, JSONObject().put("type", "wemeet-ui-command").put("command", "close-left-panel"))
            protocol.interactive -> beginSave()
            protocol.phase == EditorProtocol.Phase.WAITING ||
                protocol.phase == EditorProtocol.Phase.UNSUPPORTED -> currentOnClose()
        }
    }
    DisposableEffect(webView) {
        val client = webView.webViewClient as? DocsWebViewClient
        client?.onLoadingChanged = { value ->
            loading = value
            if (value) {
                error = false
                protocol = EditorProtocol(currentDocId, UUID.randomUUID().toString())
            }
        }
        client?.onMainFrameError = { error = true; loading = false }
        client?.onPanelState = { left, _ -> leftPanel = left }
        client?.onEditorNavigate = ::navigateAfterSave
        client?.onEditorEvent = { event ->
            when (event.optString("type")) {
                "wemeet-editor-navigate" -> if (event.optString("docId") == protocol.docId &&
                    event.optString("editorInstanceId") == protocol.instanceId) navigateAfterSave(event.optString("url"))
                "wemeet-editor-ready" -> protocol = protocol.ready(event.optString("docId"), event.optString("editorInstanceId"),
                    event.optInt("protocolVersion"), event.optJSONObject("capabilities")?.optBoolean("saveConfirmation") == true)
                "wemeet-save-result" -> {
                    val updated = protocol.result(event.optString("docId"), event.optString("editorInstanceId"),
                        event.optString("requestId"), event.optBoolean("success"))
                    val completed = protocol.phase == EditorProtocol.Phase.SAVING && updated.phase == EditorProtocol.Phase.CLOSED
                    protocol = updated
                    if (completed) {
                        val target = pendingNavigation
                        pendingNavigation = null
                        if (target == null || DocLinks.docIdFromUrl(target, BuildConfig.WE_MEET_DOCS_URL) == null) {
                            currentOnClose()
                        } else {
                            val uri = Uri.parse(target)
                            val destination = uri.buildUpon().clearQuery().apply {
                                uri.queryParameterNames.filterNot { it == "chrome" || it == "embed" }.forEach { name ->
                                    uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
                                }
                                appendQueryParameter("chrome", "editor")
                                appendQueryParameter("embed", "1")
                            }.build().toString()
                            if (destination == currentUrl) webView.reload() else currentUrl = destination
                        }
                    }
                }
            }
        }
        onDispose {
            client?.onLoadingChanged = null
            client?.onMainFrameError = null
            client?.onPanelState = null
            client?.onEditorEvent = null
            client?.onEditorNavigate = null
            releaseDocsWebView(webView)
        }
    }
    LaunchedEffect(webView, currentUrl, entryAttempt) { loadDocsEditorEntry(context, webView, currentUrl) }
    LaunchedEffect(protocol.phase) {
        if (protocol.interactive) previouslyInteractive = true
    }
    LaunchedEffect(protocol.instanceId, loading, error) {
        if (!loading && !error) {
            // onPageFinished covers the HTML, not Next's lazy editor chunks or
            // document fetch. A cold start can mount the bridge much later.
            val started = SystemClock.elapsedRealtime()
            while (protocol.awaitingReady) {
                postToDocs(webView, message("wemeet-host-hello"))
                if (protocol.phase == EditorProtocol.Phase.WAITING &&
                    SystemClock.elapsedRealtime() - started >= 30_000) {
                    Log.w("WeMeetDocs", "[editor] ready handshake delayed; continuing to wait")
                    protocol = protocol.timedOut()
                }
                // A timeout offers retry, but is not proof of incompatibility.
                // Keep the same challenge so late readiness can recover in place.
                delay(if (protocol.phase == EditorProtocol.Phase.WAITING) 500 else 2_000)
            }
        }
    }
    LaunchedEffect(protocol.requestId) {
        val request = protocol.requestId ?: return@LaunchedEffect
        delay(15_000)
        if (protocol.requestId == request) {
            postToDocs(webView, message("wemeet-resume-editor", request))
            protocol = protocol.timedOut()
        }
    }
    BackHandler { requestClose() }
    Scaffold(topBar = { WeMeetTopBar(title = stringResource(R.string.docs_editor_title), onClose = requestClose) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize(), update = {
                val visible = protocol.phase != EditorProtocol.Phase.WAITING &&
                    protocol.phase != EditorProtocol.Phase.UNSUPPORTED
                it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                it.isEnabled = protocol.interactive
                it.isFocusable = protocol.interactive
                it.isFocusableInTouchMode = protocol.interactive
            })
            if (protocol.phase == EditorProtocol.Phase.WAITING) {
                DocsLoadStateOverlay(loading = !error, error = error, onRetry = ::retryEntry)
            }
            if (protocol.phase == EditorProtocol.Phase.SAVING) {
                WeMeetLoading()
            }
            if (protocol.phase == EditorProtocol.Phase.UNSUPPORTED) {
                WeMeetErrorState(message = stringResource(R.string.docs_editor_incompatible), onRetry = ::retryEntry)
            }
        }
    }
    if (protocol.phase == EditorProtocol.Phase.FAILED) {
        AlertDialog(
            onDismissRequest = { pendingNavigation = null; protocol = protocol.copy(phase = EditorProtocol.Phase.READY) },
            title = { Text(stringResource(R.string.docs_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.docs_editor_pending_work)) },
            confirmButton = { TextButton(onClick = { beginSave() }) { Text(stringResource(R.string.docs_editor_unsaved_save)) } },
            dismissButton = { TextButton(onClick = {
                pendingNavigation = null
                protocol = protocol.copy(phase = EditorProtocol.Phase.READY)
            }) { Text(stringResource(R.string.docs_editor_unsaved_continue)) } },
        )
    }
    if (showReloadNotice) {
        AlertDialog(
            onDismissRequest = { showReloadNotice = false },
            text = { Text(stringResource(R.string.docs_editor_reloaded)) },
            confirmButton = { TextButton(onClick = { showReloadNotice = false }) {
                Text(stringResource(R.string.ok))
            } },
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
    if (DocLinks.docIdFromUrl(url, BuildConfig.WE_MEET_DOCS_URL) != null) {
        DocsEditorScreen(url, onClose)
        return
    }
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
            releaseDocsWebView(webView)
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
        Box(
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
