package com.we.meet.feature.assistant.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.assistant.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.components.WeMeetTopBar
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun AssistantHistoryRow.displayText(context: Context): String = if (role == "translation") {
    "${Locale(sourceLanguage).getDisplayLanguage(Locale.getDefault())} → ${Locale(targetLanguage).getDisplayLanguage(Locale.getDefault())}\n$source\n$text"
} else "${context.getString(if (role == "user") R.string.assistant_history_you else R.string.assistant_history_ai)}: $text"

@Composable
fun HistoryTextActions(text: String, replay: (() -> Unit)? = null, replayEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (replay != null) IconButton(onClick = replay, enabled = replayEnabled) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.assistant_history_replay))
        }
        IconButton(onClick = {
            val message = if (text.length > 100000) R.string.assistant_history_copy_large else {
                runCatching {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(
                        context.getString(R.string.assistant_history_title), text))
                }.fold({ R.string.assistant_history_copied }, { R.string.assistant_history_copy_large })
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.assistant_history_copy)) }
        IconButton(enabled = !sharing, onClick = {
            sharing = true
            scope.launch {
                try {
                    val intent = withContext(Dispatchers.IO) { historyShareIntent(context, text) }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.assistant_history_share)))
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { Toast.makeText(context, R.string.assistant_history_share_failed, Toast.LENGTH_SHORT).show() }
                finally { sharing = false }
            }
        }) { Icon(Icons.Default.Share, stringResource(R.string.assistant_history_share)) }
    }
}

@Composable
fun AssistantHistoryPreference(store: AssistantHistoryStore?, kind: String, enabled: Boolean = true) {
    if (store == null) return
    val save by store.enabled(kind).collectAsStateWithLifecycle()
    val failed by store.error.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = Dimens.ScreenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_history_save), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = save, onCheckedChange = { store.setEnabled(kind, it) }, enabled = enabled)
        }
        if (failed) Text(stringResource(R.string.assistant_history_error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantHistoryScreen(store: AssistantHistoryStore, onBack: () -> Unit, deps: com.we.meet.feature.assistant.AssistantDeps? = null) {
    val context = LocalContext.current
    val entries by store.entries.collectAsStateWithLifecycle()
    val failed by store.error.collectAsStateWithLifecycle()
    val summaryVm: AssistantSummaryViewModel? = if (deps != null) androidx.lifecycle.viewmodel.compose.viewModel(
        key = "assistant-summary:${deps.assistantAccount}", factory = AssistantSummaryViewModel.Factory(store, deps)) else null
    val requests = summaryVm?.requests?.collectAsStateWithLifecycle()?.value.orEmpty()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        searching = false
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val entry = entries.firstOrNull { it.id == selected }
    val back: () -> Unit = {
        when {
            selected != null -> selected = null
            searching -> closeSearch()
            else -> onBack()
        }
    }
    BackHandler(onBack = back)
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Scaffold(topBar = {
        WeMeetTopBar(title = stringResource(R.string.assistant_history_title), onBack = back,
            actions = {
                if (entry == null) IconButton(onClick = { if (searching) closeSearch() else searching = true }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search,
                        stringResource(if (searching) R.string.assistant_history_close_search else R.string.assistant_history_search))
                }
                if (entry != null) HistoryTextActions(entry.rows.joinToString("\n\n") { it.displayText(context) })
                if (entries.isNotEmpty()) IconButton(onClick = { deleting = entry?.id ?: "all" }) {
                    Icon(Icons.Default.Delete, stringResource(if (entry == null) R.string.assistant_history_clear else R.string.assistant_history_delete))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.ScreenPadding)) {
            if (failed) Text(stringResource(R.string.assistant_history_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (entry == null) {
                Text(stringResource(R.string.assistant_history_local_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (searching) {
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.assistant_history_search)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
                }
                val filtered = entries.filter { item -> query.isBlank() || item.rows.any {
                    it.text.contains(query, ignoreCase = true) || it.source.contains(query, ignoreCase = true)
                } }
                if (filtered.isEmpty()) Text(stringResource(R.string.assistant_history_empty), Modifier.padding(vertical = Dimens.SpaceL))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM), contentPadding = PaddingValues(vertical = Dimens.SpaceL)) {
                    items(filtered, key = { it.id }) { item ->
                        Card(onClick = { focusManager.clearFocus(); keyboard?.hide(); selected = item.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.SpaceL)) {
                                Text(stringResource(if (item.kind == "call") R.string.assistant_history_call else R.string.assistant_history_translation), style = MaterialTheme.typography.titleMedium)
                                Text(formatter.format(Date(item.startedAt)), style = MaterialTheme.typography.labelMedium)
                                Text(item.rows.first().text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else {
                Text(formatter.format(Date(entry.startedAt)), style = MaterialTheme.typography.labelMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM), contentPadding = PaddingValues(vertical = Dimens.SpaceL)) {
                    if (summaryVm != null || entry.summary != null) item(key = "summary") {
                        AssistantSummaryPanel(entry, requests[entry.id], summaryVm?.let { { it.generate(entry.id) } }) { index, done -> store.setTodo(entry.id, index, done) }
                    }
                    items(entry.rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Dimens.SpaceL)) {
                                SelectionContainer { Text(row.displayText(context)) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(if (id == "all") R.string.assistant_history_clear else R.string.assistant_history_delete)) },
            text = { Text(stringResource(R.string.assistant_history_delete_hint)) },
            confirmButton = { TextButton(onClick = {
                if (id == "all") store.clear() else store.delete(id)
                selected = null; deleting = null
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
