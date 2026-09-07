package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment

import com.we.meet.feature.docs.util.docsRunCatching as runCatching

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
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetInlineLoading
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 原生历史版本列表；预览和恢复使用 Web 协作编辑器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocVersionsSheet(
    deps: DocsDeps,
    docId: String,
    onDismiss: () -> Unit,
    onOpenVersion: (String) -> Unit,
) {
    val context = LocalContext.current
    val vm: DocVersionsViewModel = viewModel(
        key = "versions:$docId",
        factory = viewModelFactory {
            initializer { DocVersionsViewModel(deps.docsRepository, docId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(message) {
        message?.let { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
            vm.clearMessage()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth().fillMaxHeight(0.75f)
                .padding(bottom = Dimens.SpaceM),
        ) {
            DocsSheetHeader(stringResource(R.string.docs_versions), onDismiss,
                stringResource(R.string.docs_versions_help))
            Box(Modifier.weight(1f).padding(top = Dimens.SpaceM)) {
                when {
                    state.loading -> WeMeetLoading()
                    state.error -> WeMeetErrorState(
                        onRetry = vm::load,
                        message = stringResource(R.string.docs_load_error),
                    )
                    state.versions.isEmpty() -> com.we.meet.ui.components.WeMeetEmptyState(
                        title = stringResource(R.string.docs_versions_empty),
                        description = stringResource(R.string.docs_versions_empty_help),
                        icon = Icons.Outlined.History,
                    )
                    else -> LazyColumn {
                        items(state.versions, key = { it.versionId }) { version ->
                            Row(Modifier.fillMaxWidth().clickable { onOpenVersion(version.versionId) }
                                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(formatIsoTime(version.lastModified), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(if (version.isLatest) R.string.docs_version_current else R.string.docs_version_preview),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = Dimens.ScreenPadding))
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
                            hasMore = dto.isTruncated && dto.nextVersionIdMarker != null,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    fun loadMore() {
        if (_state.value.loading || _state.value.loadingMore) return
        val marker = nextMarker ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching { repo.versions(docId, marker = marker) }
                .onSuccess { dto ->
                    nextMarker = dto.nextVersionIdMarker
                    _state.update {
                        it.copy(
                            versions = (it.versions + dto.versions).distinctBy { version -> version.versionId },
                            loadingMore = false,
                            hasMore = dto.isTruncated && dto.nextVersionIdMarker != null,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loadingMore = false) }; _message.value = R.string.docs_load_error }
        }
    }

}
