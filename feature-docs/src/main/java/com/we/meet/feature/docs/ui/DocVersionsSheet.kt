package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.DocsRepository
import com.we.meet.feature.docs.data.net.DocsVersionMetaDto
import com.we.meet.feature.docs.util.formatIsoTime
import com.we.meet.ui.components.DestructiveConfirmDialog
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 版本列表 + 恢复(设计文档 §4.4 版本):S3 对象版本,恢复 = 旧版 base64 透传 PATCH。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocVersionsSheet(
    deps: DocsDeps,
    docId: String,
    onDismiss: () -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val vm: DocVersionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocVersionsViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<DocsVersionMetaDto?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(message) {
        message?.let { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
            vm.clearMessage()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.SpaceXl),
        ) {
            Text(
                text = stringResource(R.string.docs_versions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Box(Modifier.padding(top = Dimens.SpaceM)) {
                when {
                    state.loading -> WeMeetLoading()
                    state.error -> WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.versions.isEmpty() -> com.we.meet.ui.components.WeMeetEmptyState(
                        title = stringResource(R.string.docs_versions_empty),
                    )
                    else -> LazyColumn {
                        items(state.versions, key = { it.versionId }) { version ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                            ) {
                                Text(
                                    text = if (version.isLatest) {
                                        stringResource(
                                            R.string.docs_version_line_latest,
                                            formatIsoTime(version.lastModified),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.docs_version_line,
                                            formatIsoTime(version.lastModified),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = { restoreTarget = version },
                                    enabled = !state.restoring,
                                ) {
                                    Text(stringResource(R.string.docs_restore))
                                }
                            }
                        }
                        if (state.loadingMore) {
                            item(key = "more") { WeMeetInlineLoading() }
                        } else if (state.hasMore) {
                            item(key = "load-more") {
                                TextButton(
                                    onClick = vm::loadMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.docs_versions_more))
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbarHostState)
        }
    }

    restoreTarget?.let { version ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.docs_restore_title),
            message = stringResource(
                R.string.docs_restore_message,
                formatIsoTime(version.lastModified),
            ),
            confirmLabel = stringResource(R.string.docs_restore),
            dismissLabel = stringResource(R.string.docs_cancel),
            onConfirm = {
                restoreTarget = null
                vm.restore(version.versionId) { onRestored() }
            },
            onDismiss = { restoreTarget = null },
        )
    }
}

class DocVersionsViewModel(
    private val repo: DocsRepository,
    private val docId: String,
) : ViewModel() {

    data class UiState(
        val versions: List<DocsVersionMetaDto> = emptyList(),
        val loading: Boolean = false,
        val error: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val restoring: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    private var nextMarker: String? = null

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            runCatching { repo.versions(docId, marker = null) }
                .onSuccess { dto ->
                    nextMarker = dto.nextVersionIdMarker
                    _state.update {
                        it.copy(
                            versions = dto.versions,
                            loading = false,
                            hasMore = dto.nextVersionIdMarker != null,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    fun loadMore() {
        val marker = nextMarker ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching { repo.versions(docId, marker = marker) }
                .onSuccess { dto ->
                    nextMarker = dto.nextVersionIdMarker
                    _state.update {
                        it.copy(
                            versions = it.versions + dto.versions,
                            loadingMore = false,
                            hasMore = dto.nextVersionIdMarker != null,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }

    fun restore(versionId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(restoring = true) }
            val result = runCatching {
                val version = repo.version(docId, versionId)
                repo.restoreContent(docId, version.content)
            }
            _state.update { it.copy(restoring = false) }
            result
                .onSuccess {
                    _message.value = R.string.docs_version_restored
                    onDone()
                }
                .onFailure { _message.value = R.string.docs_version_restore_failed }
        }
    }
}
