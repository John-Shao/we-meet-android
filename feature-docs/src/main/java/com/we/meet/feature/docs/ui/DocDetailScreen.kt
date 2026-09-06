package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import coil.compose.LocalImageLoader
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
    onOpenEditor: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: DocDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocDetailViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var showComments by rememberSaveable { mutableStateOf(false) }
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var showShare by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    // 30s 轻轮询,仅前台可见时(设计文档 §4.7.4);回到前台先重拉一次文档/权限,
    // 覆盖「PC 批准 ask-for-access 后 Android 详情恢复可见」(§4.7.6 用例 7)。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.load()
            while (true) {
                delay(POLL_INTERVAL_MS)
                vm.pollContent()
            }
        }
    }

    val doc = state.doc

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = doc?.displayTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.docs_untitled),
                onBack = onBack,
                actions = {
                    if (doc != null) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                imageVector = if (doc.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(
                                    if (doc.isFavorite) R.string.cd_docs_unfavorite else R.string.cd_docs_favorite,
                                ),
                                tint = if (doc.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.cd_docs_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                if (doc.abilities.canRename) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.docs_rename_title), softWrap = false) },
                                        onClick = {
                                            menuExpanded = false
                                            showRename = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.docs_copy_link), softWrap = false) },
                                    onClick = {
                                        menuExpanded = false
                                        copyLink(context, deps.docsBaseUrl, doc)
                                    },
                                )
                                if (doc.abilities.move) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.docs_move_title), softWrap = false) },
                                        onClick = {
                                            menuExpanded = false
                                            showMove = true
                                        },
                                    )
                                }
                                if (doc.abilities.destroy) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.docs_delete_title),
                                                color = MaterialTheme.colorScheme.error,
                                                softWrap = false,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            showDelete = true
                                        },
                                    )
                                }
                            }
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
                state.error -> WeMeetErrorState(
                    onRetry = vm::load,
                    message = stringResource(R.string.docs_load_error),
                )
                doc != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimens.ScreenPadding),
                ) {
                    Text(
                        text = doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = Dimens.SpaceL),
                    )
                    Text(
                        text = buildInfoLine(doc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.SpaceXs),
                    )
                    CompositionLocalProvider(LocalImageLoader provides deps.docsMediaLoader) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                    blocks = state.blocks,
                                    onOpenDoc = onOpenDoc,
                                    onOpenUrl = { url ->
                                        if (DocLinks.docIdFromUrl(url) != null) {
                                            DocLinks.docIdFromUrl(url)?.let(onOpenDoc)
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
                    if (doc.abilities.update) {
                        PrimaryButton(
                            text = stringResource(R.string.docs_edit),
                            onClick = {
                                onOpenEditor(DocLinks.editorUrl(deps.docsBaseUrl, doc.id))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = Dimens.SpaceS),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.SpaceM),
                    ) {
                        SecondaryButton(
                            text = stringResource(R.string.docs_comments),
                            onClick = { showComments = true },
                            modifier = Modifier.weight(1f).padding(end = Dimens.SpaceXs),
                        )
                        SecondaryButton(
                            text = stringResource(R.string.docs_versions),
                            onClick = { showVersions = true },
                            modifier = Modifier.weight(1f).padding(horizontal = Dimens.SpaceXs),
                        )
                        PrimaryButton(
                            text = stringResource(R.string.docs_share),
                            onClick = { showShare = true },
                            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceXs),
                        )
                    }
                }
            }
        }
    }

    if (showRename) {
        DocRenameDialogInternal(
            doc = doc,
            onDismiss = { showRename = false },
            onConfirm = { newTitle ->
                vm.rename(newTitle)
                showRename = false
            },
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
            onMove = { targetId, position ->
                showMove = false
                vm.move(targetId, position, onMoved = onBack)
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
            onRestored = vm::loadContent,
        )
    }

    if (showShare && doc != null) {
        DocShareSheet(
            deps = deps,
            doc = doc,
            onDismiss = { showShare = false },
            onDocChanged = vm::load,
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
 * 复制文档链接(§4.7.3:Android 复制链接 → PC 打开,同 realm SSO 免登直达)。
 * 写系统剪贴板并把文档可见的 Web URL 放进去。
 */
private fun copyLink(
    context: android.content.Context,
    docsBaseUrl: String,
    doc: com.we.meet.feature.docs.data.net.DocumentDto,
) {
    val url = com.we.meet.feature.docs.util.DocLinks.webUrl(docsBaseUrl, doc.id)
    runCatching {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("doc-link", url))
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

@Composable
private fun DocRenameDialogInternal(
    doc: com.we.meet.feature.docs.data.net.DocumentDto?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(doc?.id) { mutableStateOf(doc?.displayTitle ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.docs_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.docs_create_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.docs_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.docs_cancel))
            }
        },
    )
}

private const val POLL_INTERVAL_MS = 30_000L
