package com.we.meet.feature.im.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.ImBridgeRepository
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
internal fun DocCardAccessDialog(deps: ImDeps, cid: String, docId: String, title: String, onDismiss: () -> Unit) {
    val instance = androidx.compose.runtime.saveable.rememberSaveable { java.util.UUID.randomUUID().toString() }
    val vm: DocCardAccessViewModel = viewModel(key = "doc-card-access:$instance", factory = viewModelFactory {
        initializer { DocCardAccessViewModel(ImSession.get(deps).bridge, docId, cid) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(stringResource(R.string.im_doc_card_access)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (state.busy) WeMeetInlineLoading()
                if (state.loaded) {
                    if (state.canManage) {
                        listOf("reader", "editor").forEach { role ->
                            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget)
                                .selectable(state.role == role, enabled = !state.busy, role = Role.RadioButton,
                                    onClick = { vm.choose(role) }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(state.role == role, onClick = null, enabled = !state.busy)
                                Text(stringResource(if (role == "reader") R.string.im_doc_access_reader else R.string.im_doc_access_editor))
                            }
                        }
                        Text(stringResource(R.string.im_doc_access_help), style = MaterialTheme.typography.bodySmall)
                    } else Text(stringResource(R.string.im_doc_access_denied), Modifier.padding(top = Dimens.SpaceM))
                }
                if (state.error) Text(stringResource(R.string.im_doc_access_failed), color = MaterialTheme.colorScheme.error)
                if (state.saved) Text(stringResource(R.string.im_doc_access_saved), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            if (!state.loaded) TextButton(onClick = vm::load, enabled = !state.busy) { Text(stringResource(R.string.im_doc_access_retry)) }
            else if (state.canManage) TextButton(onClick = vm::save, enabled = !state.busy && !state.saved) {
                Text(stringResource(R.string.im_doc_access_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.im_doc_access_close)) } },
    )
}

internal class DocCardAccessViewModel(private val repo: ImBridgeRepository, private val docId: String, private val cid: String) : ViewModel() {
    data class State(val role: String = "reader", val loaded: Boolean = false, val canManage: Boolean = false,
        val busy: Boolean = false, val error: Boolean = false, val saved: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    init { load() }
    fun choose(role: String) {
        if (!state.value.busy && state.value.canManage && role in listOf("reader", "editor"))
            mutableState.update { it.copy(role = role, saved = false) }
    }
    fun load() = request(null)
    fun save() { if (state.value.canManage && state.value.loaded) request(state.value.role) }
    private fun request(role: String?) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = false, saved = false) }
        viewModelScope.launch {
            try {
                val result = repo.docChatAccess(docId, cid, role)
                mutableState.update { it.copy(role = result["role"] as? String ?: "reader", loaded = true,
                    canManage = result["can_manage"] == true, saved = role != null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = true) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
