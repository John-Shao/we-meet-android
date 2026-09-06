package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.data.net.DocsMovePositions
import com.we.meet.ui.components.PrimaryButton
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 移动目标选择器(设计文档 §4.4 移动):懒加载树 + 顶层选项。
 *
 * 顶层 = 与第一棵根文档并列(left);选中某个文档 = 移入其内部(last-child)。
 * 正在移动的文档从树里排除(不能移入自己)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocMoveSheet(
    deps: DocsDeps,
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onMove: (targetId: String, position: String) -> Unit,
) {
    var roots by remember { mutableStateOf<List<DocumentDto>>(emptyList()) }
    var rootsLoading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<DocMoveSheetSelection>(DocMoveSheetSelection.None) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching {
            deps.docsRepository.list(page = 1, pageSize = MAX_PAGE, ordering = "title")
        }.onSuccess { page ->
            roots = page.results.filter { it.id != doc.id }
            rootsLoading = false
        }.onFailure {
            rootsLoading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                text = stringResource(R.string.docs_move_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Box(Modifier.padding(top = Dimens.SpaceM)) {
                when {
                    rootsLoading -> WeMeetInlineLoading()
                    else -> LazyColumn {
                        if (roots.isNotEmpty()) {
                            item(key = "top") {
                                MoveRow(
                                    icon = Icons.Outlined.Home,
                                    label = stringResource(R.string.docs_move_root),
                                    depth = 0,
                                    selected = selected == DocMoveSheetSelection.Top,
                                    expandable = false,
                                    expanded = false,
                                    onToggle = null,
                                    onSelect = { selected = DocMoveSheetSelection.Top },
                                )
                            }
                        }
                        items(roots, key = { "root-${it.id}" }) { root ->
                            MoveNodeRow(
                                deps = deps,
                                node = root,
                                docId = doc.id,
                                depth = 0,
                                selected = selected,
                                onSelect = { selected = it },
                            )
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL)) {
                PrimaryButton(
                    text = stringResource(R.string.docs_move_confirm),
                    enabled = selected != DocMoveSheetSelection.None,
                    onClick = {
                        when (val sel = selected) {
                            is DocMoveSheetSelection.Into -> onMove(sel.docId, DocsMovePositions.LAST_CHILD)
                            DocMoveSheetSelection.Top -> {
                                val firstRoot = roots.firstOrNull()
                                if (firstRoot != null) onMove(firstRoot.id, DocsMovePositions.LEFT)
                            }
                            DocMoveSheetSelection.None -> Unit
                        }
                    },
                )
            }
        }
    }
}

/** Move target: none / into a document / top level (left of first root). */
private sealed interface DocMoveSheetSelection {
    data object None : DocMoveSheetSelection
    data object Top : DocMoveSheetSelection
    data class Into(val docId: String) : DocMoveSheetSelection
}

@Composable
private fun MoveNodeRow(
    deps: DocsDeps,
    node: DocumentDto,
    docId: String,
    depth: Int,
    selected: DocMoveSheetSelection,
    onSelect: (DocMoveSheetSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var children by remember { mutableStateOf<List<DocumentDto>>(emptyList()) }
    var childrenLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        MoveRow(
            icon = if (node.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
            label = node.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
            depth = depth,
            selected = selected == DocMoveSheetSelection.Into(node.id),
            expandable = node.numchild > 0,
            expanded = expanded,
            onToggle = if (node.numchild > 0) {
                {
                    expanded = !expanded
                    if (expanded && children.isEmpty()) {
                        childrenLoading = true
                        scope.launch {
                            runCatching {
                                deps.docsRepository.children(node.id, page = 1, pageSize = MAX_PAGE)
                            }.onSuccess { page ->
                                children = page.results.filter { it.id != docId }
                                childrenLoading = false
                            }.onFailure {
                                childrenLoading = false
                            }
                        }
                    }
                }
            } else {
                null
            },
            onSelect = { onSelect(DocMoveSheetSelection.Into(node.id)) },
        )
        if (expanded) {
            if (childrenLoading) {
                Box(Modifier.padding(start = Dimens.SpaceXl)) { WeMeetInlineLoading() }
            } else {
                children.forEach { child ->
                    MoveNodeRow(
                        deps = deps,
                        node = child,
                        docId = docId,
                        depth = depth + 1,
                        selected = selected,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    depth: Int,
    selected: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(
                start = Dimens.ScreenPadding + Dimens.SpaceM * depth,
                end = Dimens.ScreenPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.IconMedium),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.SpaceM),
        )
        if (expandable) {
            IconButton(onClick = { onToggle?.invoke() }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.cd_docs_collapse else R.string.cd_docs_expand,
                    ),
                )
            }
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

private const val MAX_PAGE = 200
