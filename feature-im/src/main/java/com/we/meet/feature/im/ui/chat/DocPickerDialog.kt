package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.DocHit

/**
 * 分享云文档到聊天(入口 A):聊天「+」面板「云文档」弹出的文档选择器——
 * 搜索 + 多选 + 已选计数 + 发送,布局参照 [ForwardPicker]。数据源为
 * [fetchDocs](空 query 即最近文档,与 Web DocPickerDialog 同一后端接口)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocPickerDialog(
    fetchDocs: suspend (query: String) -> List<DocHit>,
    onSend: (List<DocHit>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var query by rememberSaveable { mutableStateOf("") }
        var docs by remember { mutableStateOf<List<DocHit>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

        LaunchedEffect(query) {
            loading = true
            error = false
            runCatching { fetchDocs(query) }
                .onSuccess { docs = it; loading = false }
                .onFailure { error = true; loading = false }
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.im_action_cancel))
                    }
                    Text(
                        text = stringResource(R.string.im_doc_picker_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    // Balances the leading close button so the title stays visually centered.
                    Spacer(Modifier.width(48.dp))
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.im_doc_picker_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        error -> Text(
                            text = stringResource(R.string.im_doc_picker_error),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        loading && docs.isEmpty() -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                        docs.isEmpty() -> Text(
                            text = stringResource(R.string.im_doc_picker_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(docs.size) { i ->
                                val d = docs[i]
                                DocPickerRow(
                                    doc = d,
                                    checked = d.id in selected,
                                    onClick = {
                                        selected = if (d.id in selected) selected - d.id
                                        else selected + d.id
                                    },
                                )
                            }
                        }
                    }
                }

                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.im_doc_picker_selected, selected.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onSend(docs.filter { it.id in selected }) },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.im_doc_picker_send, selected.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocPickerRow(
    doc: DocHit,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = doc.title.ifBlank { stringResource(R.string.im_preview_doc) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
