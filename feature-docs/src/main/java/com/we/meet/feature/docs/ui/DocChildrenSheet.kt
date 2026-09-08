package com.we.meet.feature.docs.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct children keep the server order; each row opens its own native detail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocChildrenSheet(
    deps: DocsDeps,
    doc: DocumentDto,
    onDismiss: () -> Unit,
    onOpenDoc: (String) -> Unit,
    currentDocId: String? = null,
    initialChildren: List<DocumentDto>? = null,
    onOpenParent: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var children by remember(doc.id) { mutableStateOf(initialChildren.orEmpty()) }
    var page by remember(doc.id) { mutableIntStateOf(0) }
    var hasMore by remember(doc.id) { mutableStateOf(initialChildren == null) }
    var busy by remember(doc.id) { mutableStateOf(false) }
    var error by remember(doc.id) { mutableStateOf(false) }
    var title by remember(doc.id) { mutableStateOf("") }
    fun load() {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try {
                val result = deps.docsRepository.children(doc.id, page + 1)
                children = (children + result.results).distinctBy { it.id }
                page += 1
                hasMore = result.next != null
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = true
            } finally { busy = false }
        }
    }
    LaunchedEffect(doc.id) { if (initialChildren == null) load() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialChildren.orEmpty().indexOfFirst { it.id == currentDocId }.coerceAtLeast(0),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).imePadding().padding(bottom = Dimens.SpaceM)) {
            DocsSheetHeader(stringResource(if (currentDocId == null) R.string.docs_children else R.string.docs_directory), onDismiss, doc.displayTitle)
            if (onOpenParent != null) TextButton(onClick = onOpenParent,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
                Text(stringResource(R.string.docs_open_parent), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (doc.abilities.childrenCreate && currentDocId == null) {
                Row(Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.weight(1f),
                        singleLine = true, label = { Text(stringResource(R.string.docs_create_hint)) })
                    TextButton(enabled = title.isNotBlank() && !busy, onClick = {
                        busy = true
                        error = false
                        scope.launch {
                            try {
                                val child = deps.docsRepository.createChild(doc.id, title.trim())
                                onOpenDoc(child.id)
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { error = true
                            } finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.docs_create_confirm)) }
                }
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(children, key = { it.id }) { child ->
                    val selected = child.id == currentDocId
                    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = { onOpenDoc(child.id) })
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
                        DocsFileIcon(child.isFolder)
                        Text(child.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                            style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (selected) {
                            Text(stringResource(R.string.docs_current_document), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Icon(Icons.Outlined.Check, null)
                        } else Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                    }
                }
                if (busy) item { WeMeetInlineLoading() }
                if (error) item { WeMeetInlineErrorState(onRetry = { load() }, message = stringResource(R.string.docs_load_error)) }
                if (!busy && hasMore && !error) item {
                    TextButton(onClick = { load() }) { Text(stringResource(R.string.docs_load_more)) }
                }
                if (!busy && children.isEmpty() && !error) item {
                    Text(stringResource(R.string.docs_children_empty), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
