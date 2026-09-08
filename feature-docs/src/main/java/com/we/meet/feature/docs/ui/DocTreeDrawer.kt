package com.we.meet.feature.docs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.util.DocLinks
import com.we.meet.feature.docs.util.docsRunCatching
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

private enum class TreeAction { CREATE, RENAME, DETACH, MOVE, DUPLICATE, DELETE }
private data class TreeOperation(val row: DocTreeRow, val action: TreeAction)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocTreeWorkspace(
    deps: DocsDeps,
    vm: DocTreeViewModel,
    docId: String,
    drawerState: DrawerState,
    onNavigate: (String) -> Unit,
    onExitWorkspace: () -> Unit,
    onDocChanged: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var operation by remember { mutableStateOf<TreeOperation?>(null) }
    val listState = rememberLazyListState()
    val rows = remember(state.root, state.expanded) { visibleTreeRows(state.root, state.expanded) }
    val width = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.92f, DrawerDefaults.MaximumDrawerWidth)
    fun close() { scope.launch { drawerState.close() } }
    fun navigate(id: String) {
        scope.launch {
            drawerState.close()
            if (id != docId) onNavigate(id)
        }
    }
    LaunchedEffect(docId, deps.docsRepository.accountGeneration) { vm.load(docId) }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) vm.load(docId, refresh = true)
    }
    // Locate the current page once after opening/loading, without fighting manual scrolling.
    var located by remember(drawerState.isOpen, docId) { mutableStateOf(false) }
    LaunchedEffect(drawerState.isOpen, rows, state.loading, located) {
        if (drawerState.isOpen && !state.loading && !located) {
            val index = rows.indexOfFirst { it.doc.id == docId }
            if (index >= 0) {
                val statusRows = rows.take(index).count { row ->
                    row.doc.id in state.expanded &&
                        (row.doc.id in state.loadingBranches || row.doc.id in state.failedBranches)
                }
                listState.scrollToItem(index + statusRows)
                located = true
            }
        }
    }
    BackHandler(enabled = drawerState.isOpen && operation == null) { close() }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen && operation == null,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(width)) {
                Row(Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.docs_directory), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.revealCurrent(); located = false }) {
                        Icon(Icons.Outlined.MyLocation, stringResource(R.string.docs_tree_locate))
                    }
                    IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, stringResource(R.string.docs_cancel)) }
                }
                HorizontalDivider()
                when {
                    state.currentId != docId || state.loading -> WeMeetInlineLoading()
                    state.error -> WeMeetInlineErrorState(onRetry = { vm.load(docId, refresh = true) },
                        message = stringResource(R.string.docs_navigation_error))
                    else -> LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = Dimens.SpaceS)) {
                        rows.forEach { row ->
                            item(key = row.doc.id) {
                                TreeNodeRow(row, selected = row.doc.id == docId, expanded = row.doc.id in state.expanded,
                                    onToggle = { vm.toggle(row.doc.id) }, onOpen = { navigate(row.doc.id) },
                                    onCopy = { clipboard.setText(AnnotatedString(DocLinks.webUrl(deps.docsBaseUrl, row.doc.id))) },
                                    onAction = { action -> operation = TreeOperation(row, action) })
                            }
                            if (row.doc.id in state.expanded) {
                                if (row.doc.id in state.loadingBranches) item(key = "loading:${row.doc.id}") { WeMeetInlineLoading() }
                                if (row.doc.id in state.failedBranches) item(key = "error:${row.doc.id}") {
                                    WeMeetInlineErrorState(onRetry = { vm.loadChildren(row.doc.id) }, message = stringResource(R.string.docs_navigation_error))
                                }
                            }
                        }
                    }
                }
            }
        },
        content = content,
    )
    operation?.let { selected ->
        TreeOperationDialog(deps, selected, state.root, onDismiss = { operation = null },
            onDone = { destination, exit ->
                operation = null
                if (exit) {
                    scope.launch { drawerState.close(); onExitWorkspace() }
                } else if (destination != null) {
                    if (destination == docId) { vm.load(docId, refresh = true); onDocChanged() }
                    navigate(destination)
                } else {
                    vm.load(docId, refresh = true)
                    onDocChanged()
                }
            })
    }
}

@Composable
private fun TreeNodeRow(
    row: DocTreeRow,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onAction: (TreeAction) -> Unit,
) {
    val doc = row.doc
    val title = doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) }
    var menu by remember(doc.id) { mutableStateOf(false) }
    val description = stringResource(if (selected) R.string.docs_current_document else R.string.docs_tree_level, row.level + 1)
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = Dimens.SpaceM * row.level.coerceAtMost(5)),
            verticalAlignment = Alignment.CenterVertically) {
            // Keep a touch target for the disclosure arrow; it never opens the document.
            if (doc.numchild > 0) IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    stringResource(if (expanded) R.string.docs_tree_collapse else R.string.docs_tree_expand, title))
            } else Spacer(Modifier.width(Dimens.MinTouchTarget))
            Row(Modifier.weight(1f).heightIn(min = Dimens.MinTouchTarget)
                .selectable(selected = selected, role = Role.Button, onClick = onOpen)
                .semantics { stateDescription = description },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Icon(Icons.Outlined.Description, null, Modifier.size(Dimens.IconSmall), tint = MaterialTheme.colorScheme.primary)
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected || row.level == 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.docs_tree_actions, title)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.docs_copy_link)) }, onClick = { menu = false; onCopy() })
                    if (doc.abilities.childrenCreate) DropdownMenuItem(text = { Text(stringResource(R.string.docs_tree_create)) },
                        onClick = { menu = false; onAction(TreeAction.CREATE) })
                    if (doc.abilities.canRename) DropdownMenuItem(text = { Text(stringResource(R.string.docs_rename_title)) },
                        onClick = { menu = false; onAction(TreeAction.RENAME) })
                    if (row.parentId != null) DropdownMenuItem(text = { Text(stringResource(R.string.docs_tree_detach)) },
                        enabled = doc.userRole == "owner" && doc.abilities.move, onClick = { menu = false; onAction(TreeAction.DETACH) })
                    if (doc.abilities.move) DropdownMenuItem(text = { Text(stringResource(R.string.docs_move_title)) },
                        onClick = { menu = false; onAction(TreeAction.MOVE) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.docs_duplicate)) }, enabled = doc.abilities.duplicate,
                        onClick = { menu = false; onAction(TreeAction.DUPLICATE) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.docs_delete_confirm), color = MaterialTheme.colorScheme.error) },
                        enabled = doc.abilities.destroy, onClick = { menu = false; onAction(TreeAction.DELETE) })
                }
            }
            // Like Web, expose '+' on the current node; every node also offers it in its menu.
            if (selected && doc.abilities.childrenCreate) IconButton(onClick = { onAction(TreeAction.CREATE) }) {
                Icon(Icons.Outlined.Add, stringResource(R.string.docs_tree_create))
            }
        }
    }
}

@Composable
private fun TreeOperationDialog(
    deps: DocsDeps,
    operation: TreeOperation,
    root: DocumentDto?,
    onDismiss: () -> Unit,
    onDone: (destination: String?, exit: Boolean) -> Unit,
) {
    val doc = operation.row.doc
    val repo = deps.docsRepository
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    fun execute(block: suspend () -> Pair<String?, Boolean>, complete: ((Boolean) -> Unit)? = null) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            docsRunCatching { block() }.onSuccess { (destination, exit) ->
                busy = false
                complete?.invoke(true)
                onDone(destination, exit)
            }.onFailure {
                android.util.Log.w("DocsTree", "${operation.action} failed: ${it.javaClass.simpleName}, HTTP ${(it as? retrofit2.HttpException)?.code()}")
                busy = false
                error = true
                complete?.invoke(false)
            }
        }
    }
    when (operation.action) {
        TreeAction.CREATE, TreeAction.RENAME -> {
            val creating = operation.action == TreeAction.CREATE
            DocsRenameDialog(doc = if (creating) doc.copy(title = "") else doc,
                titleRes = if (creating) R.string.docs_tree_create else R.string.docs_rename_title,
                confirmRes = if (creating) R.string.docs_create_confirm else R.string.docs_rename_confirm,
                onDismiss = onDismiss, onConfirm = { title, complete ->
                    execute({
                        if (creating) repo.createChild(doc.id, title).id to false
                        else { repo.rename(doc.id, title); null to false }
                    }, complete)
                })
        }
        TreeAction.MOVE -> DocMoveSheet(deps, doc, onDismiss = onDismiss, onMove = { target, position, complete ->
            execute({ repo.move(doc.id, target, position); doc.id to false }, complete)
        })
        else -> {
            val deleting = operation.action == TreeAction.DELETE
            val label = stringResource(when (operation.action) {
                TreeAction.DELETE -> R.string.docs_delete_confirm
                TreeAction.DETACH -> R.string.docs_tree_detach
                else -> R.string.docs_duplicate
            })
            fun submit() = execute({
                when (operation.action) {
                    TreeAction.DELETE -> { repo.delete(doc.id); operation.row.parentId to (operation.row.parentId == null) }
                    TreeAction.DETACH -> {
                        check(root != null)
                        repo.move(doc.id, root.id, "last-sibling")
                        root.id to false
                    }
                    else -> checkNotNull(repo.duplicate(doc.id)) to false
                }
            })
            // Web runs duplicate/detach immediately; only deletion asks for confirmation.
            LaunchedEffect(operation) { if (!deleting) submit() }
            if (!deleting && !busy && !error) return
            AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(label) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    Text(doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) })
                    if (deleting) Text(stringResource(R.string.docs_delete_message))
                    if (busy) WeMeetInlineLoading()
                    if (error) Text(stringResource(R.string.docs_action_failed), color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = {
                if (deleting || error) TextButton(onClick = { submit() }, enabled = !busy) {
                    Text(label, color = if (deleting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.docs_cancel)) } })
        }
    }
}
