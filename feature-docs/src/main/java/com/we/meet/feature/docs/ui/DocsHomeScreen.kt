package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
 * 云文档二级导航控制器(与任务模块的 TaskNavController 同款):由
 * [DocsHomeScreen] 在组合期注册给宿主(MainTabScreen 持有 drawerState,
 * 据此在 tab 级宿主抽屉),抽屉内容经它读取 [DocsHomeViewModel] 与导航回调。
 */
class DocsNavController(
    val viewModel: DocsHomeViewModel,
    val onOpenSearch: () -> Unit,
    val onOpenTrash: () -> Unit,
)

/**
 * 云文档 tab 根页(设计文档 §4.4 文档主页)。
 *
 * 导航设计:保留 Web 端文档的二级导航结构(所有文档/我的文档/与我分享/垃圾桶),
 * 以任务模块的同款抽屉呈现 —— 抽屉由宿主 MainTabScreen 在 tab 层持有
 * (遮罩覆盖底部导航栏,与 profile/task 抽屉一致),本页仅注册 [DocsNavController]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsHomeScreen(
    deps: DocsDeps,
    onOpenDoc: (docId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenEditor: (docId: String) -> Unit = {},
    onRegisterDocsNav: (DocsNavController) -> Unit = {},
    onOpenNavDrawer: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: DocsHomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocsHomeViewModel(deps.docsRepository, DocsHomeViewModel.Mode.HOME) }
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

    SideEffect {
        onRegisterDocsNav(DocsNavController(vm, onOpenSearch, onOpenTrash))
    }

    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }
    LaunchedEffect(Unit) { vm.refreshCounts() }

    // 列表/计数鲜度(§4.7.1):每次 Docs tab 回到前台时刷新,让 PC 端新授予的权限、
    // 邮箱邀请转正等在返回后立即可见(§4.7.6 用例 6/7)。首次组合已在 init 拉过,
    // 此效果仅负责后续 RESUME。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refresh()
            vm.refreshCounts()
        }
    }

    Scaffold(
        topBar = {
            DocsHomeHeader(
                onOpenNavDrawer = onOpenNavDrawer,
                onOpenSearch = onOpenSearch,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.cd_docs_create),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DocsListHeader(
                filter = state.filter,
                ordering = state.ordering,
                sortExpanded = showSortMenu,
                onSortChanged = { showSortMenu = it },
                onSelectOrdering = vm::setOrdering,
            )
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    vm.refresh()
                    vm.refreshCounts()
                },
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
                        onCreate = { showCreate = true },
                    )
                    else -> DocsList(
                        items = state.items,
                        loadingMore = state.loadingMore,
                        onLoadMore = vm::loadMore,
                        onOpenDoc = onOpenDoc,
                        onToggleFavorite = vm::toggleFavorite,
                        onRename = { renameTarget = it },
                        onMove = { moveTarget = it },
                        onDelete = { deleteTarget = it },
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
                    // §4.4 新建 = 建空文档 → 跳编辑画布填充(可直接写正文,不再点编辑)。
                    vm.create(title)?.let { docId ->
                        onOpenEditor(docId)
                    }
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

/** 回收站页(路由级,独立于主页;复用列表内容,不带导航抽屉)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsTrashScreen(
    deps: DocsDeps,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: DocsHomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocsHomeViewModel(deps.docsRepository, DocsHomeViewModel.Mode.TRASH) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.toasts.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }
    Scaffold(
        topBar = { WeMeetTopBar(title = stringResource(R.string.docs_trash), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> WeMeetLoading()
                state.error -> WeMeetErrorState(
                    onRetry = vm::refresh,
                    message = stringResource(R.string.docs_load_error),
                )
                state.items.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(R.string.docs_empty_trash_title),
                )
                else -> DocsList(
                    items = state.items,
                    loadingMore = state.loadingMore,
                    onLoadMore = vm::loadMore,
                    onOpenDoc = onOpenDoc,
                    onToggleFavorite = vm::toggleFavorite,
                    onRename = {},
                    onMove = {},
                    onDelete = {},
                    onRestore = vm::restore,
                    trashMode = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocsHomeHeader(
    onOpenNavDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.ActionTile)
            .padding(horizontal = Dimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenNavDrawer) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.cd_docs_nav),
            )
        }
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.docs_screen_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onOpenSearch) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.cd_docs_search),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocsListHeader(
    filter: DocsHomeViewModel.Filter,
    ordering: String,
    sortExpanded: Boolean,
    onSortChanged: (Boolean) -> Unit,
    onSelectOrdering: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (filter) {
                    DocsHomeViewModel.Filter.ALL -> R.string.docs_nav_all
                    DocsHomeViewModel.Filter.MINE -> R.string.docs_nav_mine
                    DocsHomeViewModel.Filter.SHARED -> R.string.docs_nav_shared
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { onSortChanged(true) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = stringResource(R.string.cd_docs_sort),
                )
            }
            SortDropdown(
                expanded = sortExpanded,
                current = ordering,
                onDismiss = { onSortChanged(false) },
                onSelect = onSelectOrdering,
            )
        }
    }
}

@Composable
private fun SortDropdown(
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
    onCreate: () -> Unit,
) {
    when (filter) {
        DocsHomeViewModel.Filter.SHARED -> WeMeetEmptyState(
            title = stringResource(R.string.docs_empty_shared_title),
            description = stringResource(R.string.docs_empty_shared_desc),
            icon = Icons.Outlined.Description,
        )
        else -> WeMeetEmptyState(
            title = stringResource(R.string.docs_empty_title),
            description = stringResource(R.string.docs_empty_description),
            icon = Icons.Outlined.Description,
            action = { SecondaryButton(text = stringResource(R.string.docs_create_confirm), onClick = onCreate) },
        )
    }
}

@Composable
private fun DocsList(
    items: List<DocumentDto>,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
    onToggleFavorite: (DocumentDto) -> Unit,
    onRename: (DocumentDto) -> Unit,
    onMove: (DocumentDto) -> Unit,
    onDelete: (DocumentDto) -> Unit,
    onRestore: (DocumentDto) -> Unit = {},
    trashMode: Boolean = false,
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { doc ->
            DocListItem(
                doc = doc,
                mode = if (trashMode) DocsHomeViewModel.Mode.TRASH else DocsHomeViewModel.Mode.HOME,
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
