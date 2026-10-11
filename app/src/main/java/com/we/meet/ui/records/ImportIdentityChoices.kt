package com.we.meet.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.R
import com.we.meet.data.api.RecordingImportIdentity
import com.we.meet.data.repository.RecordingUploadRepository
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun ImportIdentityChoices(repository: RecordingUploadRepository, viewer: String, initial: RecordingImportIdentity,
    disabled: Boolean, limit: Int, onIntent: (RecordingImportIdentity) -> Unit, onReady: (Boolean) -> Unit) {
    val operations = remember(repository, viewer) { object : ImportIdentityOperations {
        override suspend fun scopes(offset: Int) = repository.importScopes(viewer, offset)
        override suspend fun candidates(organization: String?, query: String, offset: Int) = repository.importCandidates(viewer, organization, query, offset)
    } }
    val controller = remember(operations) { ImportIdentityController(operations, initial) }
    val state by controller.state.collectAsState()
    val latestIntent by rememberUpdatedState(onIntent)
    val latestReady by rememberUpdatedState(onReady)
    val jobs = rememberCoroutineScope()
    var scopeMenu by remember { mutableStateOf(false) }
    SideEffect { controller.freeze(disabled) }
    DisposableEffect(controller) { onDispose { controller.close(); latestReady(false) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        try { controller.show(); controller.scopes(); controller.reload(); awaitCancellation() }
        finally { scopeMenu = false; controller.hide(); latestReady(false) }
    } }
    LaunchedEffect(state.organization, state.selected) { if (!disabled) latestIntent(state.intent) }
    LaunchedEffect(state.ready) { latestReady(state.ready) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(stringResource(R.string.import_identity_scope), style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { scopeMenu = true }, enabled = !disabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text(state.scopes.find { it.id == state.organization }?.name ?: stringResource(if (state.organization == null) R.string.import_identity_personal else R.string.import_identity_selected_scope))
            }
            DropdownMenu(expanded = scopeMenu && !disabled, onDismissRequest = { scopeMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.import_identity_personal)) }, onClick = { scopeMenu = false; jobs.launch { controller.scope(null) } })
                state.scopes.forEach { scope -> DropdownMenuItem(text = { Text(scope.name) }, enabled = scope.policy.enabled,
                    onClick = { scopeMenu = false; jobs.launch { controller.scope(scope.id) } }) }
            }
        }
        if (state.nextScopes != null) TextButton(enabled = !disabled && !state.scopeLoading,
            onClick = { jobs.launch { controller.scopes(true) } }) { Text(stringResource(R.string.import_identity_more_scopes)) }
        OutlinedTextField(state.query, { controller.query(it) }, enabled = !disabled, singleLine = true,
            label = { Text(stringResource(R.string.import_identity_search)) }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !disabled, onClick = { jobs.launch { controller.search() } }) { Text(stringResource(R.string.import_identity_search_action)) }
        Text(stringResource(R.string.import_identity_selected, state.selected.size, limit))
        if (!state.failure && !state.scopeFailure) state.selected.forEach { id ->
            state.names[id]?.let { name -> TextButton(enabled = !disabled, onClick = { controller.remove(id) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_identity_remove, name))
            } }
        }
        when {
            state.failure || state.scopeFailure -> {
                Text(stringResource(R.string.import_identity_directory_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !disabled, onClick = { jobs.launch { controller.scopes(); controller.reload() } }) { Text(stringResource(R.string.records_refresh)) }
            }
            state.loading -> Text(stringResource(R.string.import_identity_loading))
            else -> {
                if (state.page?.results.isNullOrEmpty()) Text(stringResource(R.string.import_identity_empty))
                state.page?.results.orEmpty().forEach { person ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                        Checkbox(checked = person.id in state.selected,
                            enabled = !disabled && (person.id in state.selected || state.selected.size < limit),
                            onCheckedChange = { controller.choose(person.id, limit) }, modifier = Modifier.semantics { contentDescription = person.name })
                        Text(person.name, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                    if (state.offset > 0) TextButton(enabled = !disabled, onClick = { jobs.launch { controller.page(false) } }) { Text(stringResource(R.string.import_identity_previous)) }
                    if (state.page?.nextOffset != null) TextButton(enabled = !disabled, onClick = { jobs.launch { controller.page(true) } }) { Text(stringResource(R.string.import_identity_next)) }
                }
            }
        }
        Text(stringResource(R.string.import_identity_hint), style = MaterialTheme.typography.bodySmall)
    }
}
