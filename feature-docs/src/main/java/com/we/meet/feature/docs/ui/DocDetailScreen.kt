package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.util.DocLinks
import com.we.meet.feature.docs.util.formatIsoTime
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.SecondaryButton
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineEmptyState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens

/**
 * 文档详情(M1 骨架):标题/创建者/更新时间 + 行操作(收藏/重命名/移动/删除)。
 *
 * 正文阅读态是 M2 的 BlockNote 渲染器;本期正文区给出「用网页版打开」入口,
 * 走既有的 DocsViewerScreen WebView 深链(设计文档 §4.6 兜底通道)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailScreen(
    deps: DocsDeps,
    docId: String,
    onBack: () -> Unit,
    onOpenWebUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val vm: DocDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocDetailViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
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
                    // M2 阅读态占位:本期跳既有 WebView 深链。
                    Box(Modifier.padding(top = Dimens.SpaceXxl)) {
                        Column {
                            WeMeetInlineEmptyState(
                                title = stringResource(R.string.docs_reader_pending),
                                description = stringResource(R.string.docs_reader_pending_desc),
                            )
                            SecondaryButton(
                                text = stringResource(R.string.docs_open_web),
                                onClick = {
                                    onOpenWebUrl(DocLinks.webUrl(deps.docsBaseUrl, doc.id))
                                },
                                modifier = Modifier.padding(top = Dimens.SpaceL),
                            )
                        }
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
}

@Composable
private fun buildInfoLine(doc: com.we.meet.feature.docs.data.net.DocumentDto): String {
    val creator = doc.creator?.displayName?.takeIf { it.isNotBlank() }
    val updated = formatIsoTime(doc.updatedAt)
    val parts = buildList {
        if (creator != null) add(stringResource(R.string.docs_created_by, creator))
        if (updated.isNotBlank()) add(stringResource(R.string.docs_updated_at, updated))
    }
    return parts.joinToString(" · ")
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
