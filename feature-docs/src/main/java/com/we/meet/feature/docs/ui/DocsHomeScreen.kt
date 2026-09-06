package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.SecondaryButton
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 云文档 tab 根页(设计文档 §4.4 文档主页)。
 *
 * 原生列表(全部/我的/收藏 + 排序 + 分页 + 下拉刷新 + 三态)替换常驻 WebView;
 * 行操作(收藏/重命名/移动/删除)与新建走 docs REST。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsHomeScreen(
    deps: DocsDeps,
    onOpenDoc: (docId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    DocsListScreen(
        deps = deps,
        mode = DocsHomeViewModel.Mode.HOME,
        title = stringResource(R.string.docs_screen_title),
        onBack = null,
        onOpenDoc = onOpenDoc,
        onOpenSearch = onOpenSearch,
        onOpenTrash = onOpenTrash,
    )
}

/** 回收站页(路由级,独立于主页)。 */
@Composable
fun DocsTrashScreen(
    deps: DocsDeps,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
) {
    DocsListScreen(
        deps = deps,
        mode = DocsHomeViewModel.Mode.TRASH,
        title = stringResource(R.string.docs_trash),
        onBack = onBack,
        onOpenDoc = onOpenDoc,
        onOpenSearch = null,
        onOpenTrash = null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocsListScreen(
    deps: DocsDeps,
    mode: DocsHomeViewModel.Mode,
    title: String,
    onBack: (() -> Unit)?,
    onOpenDoc: (docId: String) -> Unit,
    onOpenSearch: (() -> Unit)?,
    onOpenTrash: (() -> Unit)?,
) {
    val context = LocalContext.current
    val vm: DocsHomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocsHomeViewModel(deps.docsRepository, mode) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DocumentDto?>(null) }
    var deleteTarget by remember { mutableStateOf<DocumentDto?>(null) }
    var moveTarget by remember { mutableStateOf<DocumentDto?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    if (mode == DocsHomeViewModel.Mode.HOME) {
                        if (onOpenSearch != null) {
                            IconButton(onClick = onOpenSearch) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.cd_docs_search),
                                )
                            }
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(R.string.cd_docs_sort),
                            )
                        }
                        SortMenu(
                            expanded = showSortMenu,
                            current = state.ordering,
                            onDismiss = { showSortMenu = false },
                            onSelect = {
                                vm.setOrdering(it)
                                showSortMenu = false
                            },
                        )
                        if (onOpenTrash != null) {
                            IconButton(onClick = onOpenTrash) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(R.string.cd_docs_trash),
                                )
                            }
                        }
                        IconButton(onClick = { showCreate = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = stringResource(R.string.cd_docs_create),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (mode == DocsHomeViewModel.Mode.HOME) {
                FilterRow(
                    filter = state.filter,
                    onSelect = vm::setFilter,
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.items.isEmpty() -> WeMeetLoading()
                    state.error && state.items.isEmpty() -> WeMeetErrorState(
                        onRetry = vm::refresh,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.items.isEmpty() -> DocsEmptyState(
                        filter = state.filter,
                        mode = mode,
                        onCreate = if (mode == DocsHomeViewModel.Mode.HOME) {
                            { showCreate = true }
                        } else {
                            null
                        },
                    )
                    else -> DocsList(
                        items = state.items,
                        mode = mode,
                        loadingMore = state.loadingMore,
                        onLoadMore = vm::loadMore,
                        onOpenDoc = onOpenDoc,
                        onToggleFavorite = vm::toggleFavorite,
                        onRename = { renameTarget = it },
                        onMove = { moveTarget = it },
                        onDelete = { deleteTarget = it },
                        onRestore = vm::restore,
                    )
                }
            }
        }
    }

    if (showCreate) {
        DocCreateSheet(
            onDismiss = { showCreate = false },
            onCreate = { title ->
                showCreate = false
                scope.launch {
                    vm.create(title)?.let { onOpenDoc(it) }
                }
            },
        )
    }

    renameTarget?.let { doc ->
        DocRenameDialog(
            doc = doc,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                vm.rename(doc, newTitle)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { doc ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.docs_delete_title),
            message = stringResource(R.string.docs_delete_message),
            confirmLabel = stringResource(R.string.docs_delete_confirm),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = {
                deleteTarget = null
                vm.delete(doc)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    moveTarget?.let { doc ->
        DocMoveSheet(
            deps = deps,
            doc = doc,
            onDismiss = { moveTarget = null },
            onMove = { targetId, position ->
                moveTarget = null
                vm.move(doc, targetId, position)
            },
        )
    }
}

@Composable
private fun FilterRow(
    filter: DocsHomeViewModel.Filter,
    onSelect: (DocsHomeViewModel.Filter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter == DocsHomeViewModel.Filter.ALL,
            onClick = { onSelect(DocsHomeViewModel.Filter.ALL) },
            label = { Text(stringResource(R.string.docs_filter_all)) },
        )
        FilterChip(
            selected = filter == DocsHomeViewModel.Filter.MINE,
            onClick = { onSelect(DocsHomeViewModel.Filter.MINE) },
            label = { Text(stringResource(R.string.docs_filter_mine)) },
        )
        FilterChip(
            selected = filter == DocsHomeViewModel.Filter.FAVORITES,
            onClick = { onSelect(DocsHomeViewModel.Filter.FAVORITES) },
            label = { Text(stringResource(R.string.docs_filter_favorites)) },
        )
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortEntry(R.string.docs_sort_updated, "-updated_at", current, onSelect, onDismiss)
        SortEntry(R.string.docs_sort_created, "-created_at", current, onSelect, onDismiss)
        SortEntry(R.string.docs_sort_title, "title", current, onSelect, onDismiss)
    }
}

@Composable
private fun SortEntry(
    labelRes: Int,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes), softWrap = false) },
        onClick = {
            onSelect(value)
            onDismiss()
        },
    )
}

@Composable
private fun DocsEmptyState(
    filter: DocsHomeViewModel.Filter,
    mode: DocsHomeViewModel.Mode,
    onCreate: (() -> Unit)?,
) {
    when {
        mode == DocsHomeViewModel.Mode.TRASH -> WeMeetEmptyState(
            title = stringResource(R.string.docs_empty_trash_title),
        )
        filter == DocsHomeViewModel.Filter.FAVORITES -> WeMeetEmptyState(
            title = stringResource(R.string.docs_empty_favorites_title),
            description = stringResource(R.string.docs_empty_favorites_description),
            icon = Icons.Outlined.Description,
        )
        else -> WeMeetEmptyState(
            title = stringResource(R.string.docs_empty_title),
            description = stringResource(R.string.docs_empty_description),
            icon = Icons.Outlined.Description,
            action = onCreate?.let { onCreateAction ->
                { SecondaryButton(text = stringResource(R.string.docs_create_confirm), onClick = onCreateAction) }
            },
        )
    }
}

@Composable
private fun DocsList(
    items: List<DocumentDto>,
    mode: DocsHomeViewModel.Mode,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
    onToggleFavorite: (DocumentDto) -> Unit,
    onRename: (DocumentDto) -> Unit,
    onMove: (DocumentDto) -> Unit,
    onDelete: (DocumentDto) -> Unit,
    onRestore: (DocumentDto) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 3) onLoadMore()
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { doc ->
            DocListItem(
                doc = doc,
                mode = mode,
                onClick = { onOpenDoc(doc.id) },
                onToggleFavorite = { onToggleFavorite(doc) },
                onRename = { onRename(doc) },
                onMove = { onMove(doc) },
                onDelete = { onDelete(doc) },
                onRestore = { onRestore(doc) },
            )
        }
        if (loadingMore) {
            item(key = "loading-more") { WeMeetInlineLoading() }
        }
    }
}

/** 新建文档弹层:标题输入 + 主按钮(设计规范 §4.4 新建)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocCreateSheet(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                text = stringResource(R.string.docs_create_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.SpaceM),
                placeholder = { Text(stringResource(R.string.docs_create_hint)) },
                singleLine = true,
            )
            Box(Modifier.padding(top = Dimens.SpaceL)) {
                PrimaryButton(
                    text = stringResource(R.string.docs_create_confirm),
                    enabled = title.isNotBlank(),
                    onClick = { onCreate(title.trim()) },
                )
            }
        }
    }
}

/** 重命名弹层。 */
@Composable
private fun DocRenameDialog(
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(doc.id) { mutableStateOf(doc.displayTitle) }
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
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.docs_rename_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.docs_cancel))
            }
        },
    )
}
