package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch

internal enum class DocAction { COMMENTS, FAVORITE, VERSIONS, LINKS, MEMBERS, RENAME, CHILDREN, MOVE, DUPLICATE, WEB, DELETE }

private data class DocActionItem(val action: DocAction, val icon: ImageVector, val label: Int)

/** Reading owns the screen; secondary operations live in a scrollable sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocActionsSheet(doc: DocumentDto, info: String, onDismiss: () -> Unit, onAction: (DocAction) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun select(action: DocAction) {
        if (closing) return
        if (action == DocAction.FAVORITE) {
            onAction(action)
        } else {
            closing = true
            scope.launch {
                try {
                    sheet.hide()
                    onAction(action)
                } finally { closing = false }
            }
        }
    }
    val abilities = doc.abilities
    val quickActions = buildList {
        if (abilities.comment) add(DocActionItem(DocAction.COMMENTS, Icons.Outlined.ChatBubbleOutline, R.string.docs_comments))
        if (abilities.favorite) add(DocActionItem(DocAction.FAVORITE,
            if (doc.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (doc.isFavorite) R.string.docs_more_unfavorite else R.string.docs_more_favorite))
        if (abilities.versionsList) add(DocActionItem(DocAction.VERSIONS, Icons.Outlined.History, R.string.docs_versions))
    }
    val collaboration = buildList {
        if (abilities.retrieve) add(DocActionItem(DocAction.LINKS, Icons.Outlined.Link, R.string.docs_link_share))
        if (abilities.accessesView) add(DocActionItem(DocAction.MEMBERS, Icons.Outlined.Group, R.string.docs_share_collaborators))
    }
    val organization = buildList {
        if (abilities.canRename) add(DocActionItem(DocAction.RENAME, Icons.Outlined.DriveFileRenameOutline, R.string.docs_rename_title))
        if (abilities.childrenList) add(DocActionItem(DocAction.CHILDREN, Icons.Outlined.AccountTree, R.string.docs_tree_open))
        if (abilities.move) add(DocActionItem(DocAction.MOVE, Icons.AutoMirrored.Outlined.DriveFileMove, R.string.docs_move_title))
        if (abilities.duplicate) add(DocActionItem(DocAction.DUPLICATE, Icons.Outlined.ContentCopy, R.string.docs_duplicate))
        add(DocActionItem(DocAction.WEB, Icons.AutoMirrored.Outlined.OpenInNew, R.string.docs_open_web))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth()) {
            DocsSheetHeader(doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) }, onDismiss, info, titleMaxLines = 2)
            LazyColumn(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, bottom = Dimens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                if (quickActions.isNotEmpty()) item(key = "quick-actions") {
                    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceM),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        quickActions.forEach { item ->
                            val label = stringResource(item.label)
                            val favorite = item.action == DocAction.FAVORITE && doc.isFavorite
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium,
                                color = if (favorite) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (favorite) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            ) {
                                Column(Modifier.fillMaxWidth().heightIn(min = Dimens.ActionTile)
                                    .clickable(enabled = !closing, role = Role.Button, onClick = { select(item.action) })
                                    .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceM),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                                    Icon(item.icon, null, Modifier.size(Dimens.IconMedium))
                                    Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
                items(collaboration, key = { it.action }) { item -> DocOperationRow(item, enabled = !closing) { select(item.action) } }
                if (collaboration.isNotEmpty()) item(key = "collaboration-divider") { HorizontalDivider(Modifier.padding(vertical = Dimens.SpaceS)) }
                items(organization, key = { it.action }) { item -> DocOperationRow(item, enabled = !closing) { select(item.action) } }
                if (abilities.destroy) {
                    item(key = "destructive-divider") { HorizontalDivider(Modifier.padding(vertical = Dimens.SpaceS)) }
                    item(key = "delete") { DocOperationRow(DocActionItem(DocAction.DELETE, Icons.Outlined.DeleteOutline, R.string.docs_delete_confirm),
                        enabled = !closing, destructive = true) { select(DocAction.DELETE) } }
                }
            }
        }
    }
}

@Composable
private fun DocOperationRow(item: DocActionItem, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
        Icon(item.icon, null, Modifier.size(Dimens.IconMedium), tint = color)
        Text(stringResource(item.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
