package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun DocChildrenSheet(deps: DocsDeps, doc: DocumentDto, onDismiss: () -> Unit, onOpenDoc: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var children by remember(doc.id) { mutableStateOf(emptyList<DocumentDto>()) }
    var page by remember(doc.id) { mutableIntStateOf(0) }
    var hasMore by remember(doc.id) { mutableStateOf(true) }
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
    LaunchedEffect(doc.id) { load() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(Dimens.ScreenPadding)) {
            Text(stringResource(R.string.docs_children), style = MaterialTheme.typography.titleMedium)
            if (doc.abilities.childrenCreate) {
                Row {
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
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(children, key = { it.id }) { child ->
                    Text(child.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenDoc(child.id) }.padding(vertical = Dimens.SpaceM))
                }
                if (busy) item { WeMeetInlineLoading() }
                if (error) item { WeMeetInlineErrorState(onRetry = { load() }, message = stringResource(R.string.docs_load_error)) }
                if (!busy && hasMore && !error) item {
                    TextButton(onClick = { load() }) { Text(stringResource(R.string.docs_versions_more)) }
                }
                if (!busy && children.isEmpty() && !error) item {
                    Text(stringResource(R.string.docs_children_empty), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
