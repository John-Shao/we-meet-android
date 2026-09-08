package com.we.meet.feature.docs.ui

import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.selection.SelectionContainer

import com.we.meet.feature.docs.util.docsRunCatching as runCatching
import kotlinx.coroutines.flow.collectLatest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Scaffold
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.renderer.DocReader
import com.we.meet.feature.docs.util.DocLinks
import com.we.meet.feature.docs.util.formatIsoTime
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.SecondaryButton
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * 文档详情(M2):元数据 + BlockNote 阅读态正文 + 评论/版本/分享入口。
 *
 * 新鲜度(设计文档 §4.7.4):30s 前台轻轮询 + 内容变更提示;正文失败给重试 +
 * 「用网页版打开」兜底。编辑画布是 M3,本期正文区仍给网页版入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailScreen(
    deps: DocsDeps,
    docId: String,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
    onOpenWebUrl: (String) -> Unit,
    onShareToChat: (docId: String, title: String, url: String) -> Unit,
    onOpenEditor: (String) -> Unit = {},
    onSwitchDoc: (String) -> Unit = onOpenDoc,
    treeVm: DocTreeViewModel,
    onExitWorkspace: () -> Unit,
) {
    val context = LocalContext.current
    val vm: DocDetailViewModel = viewModel(
        key = "doc:$docId",
        factory = viewModelFactory {
            initializer { DocDetailViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var menuExpanded by rememberSaveable(docId) { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var showComments by rememberSaveable { mutableStateOf(false) }
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var sharingPage by rememberSaveable { mutableStateOf<DocSharingPage?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val treeState by treeVm.state.collectAsStateWithLifecycle()
    fun openDirectory() { drawerScope.launch { drawerState.open() } }

    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    // 30s 轻轮询,仅前台可见时(设计文档 §4.7.4);回到前台先重拉一次文档/权限,
    // 覆盖「PC 批准 ask-for-access 后 Android 详情恢复可见」(§4.7.6 用例 7)。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            com.we.meet.feature.docs.util.docsConnectivity(context).collectLatest { online ->
                if (online) {
                    vm.refresh()
                    while (true) {
                        delay(vm.pollDelayMillis)
                        vm.pollContent()
                    }
                } else vm.onOffline()
            }
        }
    }

    val doc = state.doc
    val readerState = rememberLazyListState()
    val headerScrolledAway by remember { derivedStateOf { readerState.firstVisibleItemIndex > 0 } }

    val density = androidx.compose.ui.platform.LocalDensity.current
    var editButtonHeight by remember { mutableStateOf(Dimens.ControlLarge) }

    DocTreeWorkspace(deps, treeVm, docId, drawerState,
        onNavigate = onSwitchDoc, onExitWorkspace = onExitWorkspace, onDocChanged = vm::load) {
    Scaffold(
        floatingActionButton = {
            if (doc?.abilities?.canEdit == true) FloatingActionButton(
                onClick = { onOpenEditor(DocLinks.editorUrl(deps.docsBaseUrl, doc.id)) },
                modifier = Modifier.onSizeChanged { editButtonHeight = with(density) { it.height.toDp() } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.docs_edit))
            }
        },
        topBar = {
            WeMeetTopBar(
                title = if (headerScrolledAway && doc != null) doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) }
                    else stringResource(R.string.docs_screen_title),
                onBack = onBack,
                actions = {
                    if (doc != null) {
                        IconButton(onClick = ::openDirectory) {
                            Icon(Icons.Outlined.AccountTree, contentDescription = stringResource(R.string.docs_tree_open),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        if (doc.abilities.retrieve) IconButton(onClick = {
                            onShareToChat(doc.id, doc.displayTitle.ifBlank { context.getString(R.string.docs_untitled) },
                                DocLinks.webUrl(deps.docsBaseUrl, doc.id))
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.docs_share_to_chat))
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.cd_docs_more))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> WeMeetLoading()
                state.noAccess -> DocNoAccessState(
                    requesting = state.requestingAccess,
                    requestSent = state.requestSent,
                    onRequest = vm::requestAccess,
                )
                state.error && doc == null -> WeMeetErrorState(
                    onRetry = vm::load,
                    message = stringResource(R.string.docs_load_error),
                )
                doc != null -> Column(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = vm::load,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        when {
                            state.contentLoading && state.blocks.isEmpty() -> WeMeetLoading()
                            state.contentError && state.blocks.isEmpty() -> Column {
                                WeMeetInlineErrorState(
                                    onRetry = vm::loadContent,
                                    message = stringResource(R.string.docs_load_error),
                                )
                                SecondaryButton(
                                    text = stringResource(R.string.docs_open_web),
                                    onClick = {
                                        onOpenWebUrl(DocLinks.webUrl(deps.docsBaseUrl, doc.id))
                                    },
                                )
                            }
                            else -> DocReader(
                                listState = readerState,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    bottom = if (doc.abilities.canEdit) editButtonHeight + Dimens.SpaceXxl else Dimens.SpaceXl,
                                ),
                                header = {
                                    Column(Modifier.padding(Dimens.ScreenPadding).padding(bottom = Dimens.SpaceM)) {
                                        SelectionContainer {
                                            Text(doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                                                style = MaterialTheme.typography.headlineMedium,
                                                modifier = Modifier.semantics { heading() })
                                        }
                                        Text(buildInfoLine(doc), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = Dimens.SpaceS))
                                        if (!doc.abilities.canEdit) Text(stringResource(R.string.docs_read_only),
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = Dimens.SpaceS))
                                        if (doc.depth > 1 && doc.abilities.childrenList && doc.numchild > 0) DocChildrenEntry(
                                            doc, treeState.root?.findTreeNode(docId)?.children.orEmpty(), onClick = ::openDirectory,
                                        )
                                    }
                                },
                                blocks = state.blocks,
                                imageLoader = deps.docsMediaLoader,
                                onOpenDoc = onOpenDoc,
                                onOpenUrl = { url ->
                                    if (DocLinks.docIdFromUrl(url, deps.docsBaseUrl) != null) {
                                        DocLinks.docIdFromUrl(url, deps.docsBaseUrl)?.let(onOpenDoc)
                                    } else {
                                        runCatching { uriHandler.openUri(url) }
                                    }
                                },
                                onOpenWebFallback = {
                                    onOpenWebUrl(DocLinks.webUrl(deps.docsBaseUrl, doc.id))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    } // Document tree drawer wraps the reader.

    if (menuExpanded && doc != null) {
        DocActionsSheet(doc, buildInfoLine(doc), onDismiss = { menuExpanded = false }) { action ->
            if (action != DocAction.FAVORITE) menuExpanded = false
            when (action) {
                DocAction.COMMENTS -> showComments = true
                DocAction.FAVORITE -> vm.toggleFavorite()
                DocAction.VERSIONS -> showVersions = true
                DocAction.LINKS -> sharingPage = DocSharingPage.LINKS
                DocAction.MEMBERS -> sharingPage = DocSharingPage.MEMBERS
                DocAction.RENAME -> showRename = true
                DocAction.CHILDREN -> openDirectory()
                DocAction.MOVE -> showMove = true
                DocAction.DUPLICATE -> vm.duplicate(onOpenDoc)
                DocAction.WEB -> onOpenWebUrl(DocLinks.webUrl(deps.docsBaseUrl, doc.id))
                DocAction.DELETE -> showDelete = true
            }
        }
    }

    if (showRename && doc != null) {
        DocsRenameDialog(
            doc = doc,
            onDismiss = { showRename = false },
            onConfirm = { newTitle, complete -> vm.rename(newTitle, complete) },
        )
    }

    if (showDelete) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.docs_delete_title),
            message = stringResource(R.string.docs_delete_message),
            confirmLabel = stringResource(R.string.docs_delete_confirm),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = {
                showDelete = false
                vm.delete(onDeleted = onBack)
            },
            onDismiss = { showDelete = false },
        )
    }

    if (showMove && doc != null) {
        DocMoveSheet(
            deps = deps,
            doc = doc,
            onDismiss = { showMove = false },
            onMove = { targetId, position, complete ->
                vm.move(targetId, position) { success ->
                    complete(success)
                    if (success) onBack()
                }
            },
        )
    }

    if (showComments) {
        DocCommentsSheet(
            deps = deps,
            docId = docId,
            onDismiss = { showComments = false },
            onOpenComment = { threadId ->
                showComments = false
                onOpenEditor(DocLinks.editorUrl(deps.docsBaseUrl, docId, threadId))
            },
        )
    }

    if (showVersions) {
        DocVersionsSheet(
            deps = deps,
            docId = docId,
            onDismiss = { showVersions = false },
            onOpenVersion = { versionId ->
                showVersions = false
                onOpenEditor(DocLinks.webUrl(deps.docsBaseUrl, docId) + "?embed=1&chrome=editor&version=" + android.net.Uri.encode(versionId))
            },
        )
    }

    if (sharingPage != null && doc != null) {
        DocShareSheet(
            deps = deps,
            doc = doc,
            onDismiss = { sharingPage = null },
            onDocChanged = vm::load,
            initialPage = sharingPage!!,
        )
    }
}

@Composable
private fun buildInfoLine(doc: com.we.meet.feature.docs.data.net.DocumentDto): String {
    val updated = formatIsoTime(doc.updatedAt)
    return if (updated.isNotBlank()) {
        stringResource(R.string.docs_updated_at, updated)
    } else {
        stringResource(R.string.docs_untitled)
    }
}

/**
 * 无访问权限态(§4.7.2):用户点开无权限文档(403)→ 提供「申请访问」,
 * 与 Web 端的 ask-for-access 同一链路(POST /documents/{id}/ask-for-access/)。
 */
@Composable
private fun DocNoAccessState(
    requesting: Boolean,
    requestSent: Boolean,
    onRequest: () -> Unit,
) {
    WeMeetEmptyState(
        title = stringResource(R.string.docs_ask_access_title),
        description = stringResource(R.string.docs_ask_access_desc),
        action = {
            when {
                requestSent -> Text(
                    text = stringResource(R.string.docs_ask_access_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> PrimaryButton(
                    text = stringResource(R.string.docs_ask_access),
                    enabled = !requesting,
                    onClick = onRequest,
                )
            }
        },
    )
}
