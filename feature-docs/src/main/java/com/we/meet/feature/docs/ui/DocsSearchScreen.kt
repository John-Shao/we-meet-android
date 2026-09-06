package com.we.meet.feature.docs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.we.meet.feature.docs.DocsDeps
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.feature.docs.util.formatIsoTime
import com.we.meet.ui.components.WeMeetEmptyState
import com.we.meet.ui.components.WeMeetErrorState
import com.we.meet.ui.components.WeMeetLoading
import com.we.meet.ui.theme.Dimens

/**
 * 模块内搜索页(docs `documents/search/`,与全局搜索同口径)。
 *
 * 顶栏标题位放搜索输入框 —— 设计规范 §9 已有的 design-exempt 先例:
 * 塞进 WeMeetTopBar 就得开插槽,会破坏它对 20+ 页面的「标题单行截断」保证。
 */
@Composable
fun DocsSearchScreen(
    deps: DocsDeps,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
) {
    val vm: DocsSearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DocsSearchViewModel(deps.docsRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Scaffold(
        // design-exempt: 标题位是搜索输入框(设计规范 §9 已有豁免先例)。
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_docs_back),
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Dimens.SpaceM),
                    placeholder = { Text(stringResource(R.string.docs_search_hint)) },
                    singleLine = true,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.idle -> Unit
                state.loading -> WeMeetLoading()
                state.error -> WeMeetErrorState(
                    onRetry = vm::retry,
                    message = stringResource(R.string.docs_load_error),
                )
                state.results.isEmpty() -> WeMeetEmptyState(
                    title = stringResource(R.string.docs_search_empty_title),
                    description = stringResource(R.string.docs_search_empty_description),
                    icon = Icons.Outlined.Description,
                )
                else -> SearchResults(
                    results = state.results,
                    onOpenDoc = onOpenDoc,
                    onClearFocus = { focusManager.clearFocus() },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<DocumentDto>,
    onOpenDoc: (String) -> Unit,
    onClearFocus: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { doc ->
            SearchResultRow(doc = doc, onClick = {
                onClearFocus()
                onOpenDoc(doc.id)
            })
        }
    }
}

@Composable
private fun SearchResultRow(
    doc: DocumentDto,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
    ) {
        Text(
            text = doc.displayTitle.ifBlank { stringResource(R.string.docs_untitled) },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = doc.parent?.displayTitle?.takeIf { it.isNotBlank() }?.let { parent ->
                stringResource(R.string.docs_search_in_parent, parent)
            } ?: stringResource(R.string.docs_updated_at, formatIsoTime(doc.updatedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
