package com.we.meet.feature.docs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        // design-exempt: 标题位是搜索输入框(设计规范 §9 已有豁免先例)。
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
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
                        .focusRequester(focusRequester)
                        .padding(end = Dimens.SpaceM),
                    placeholder = { Text(stringResource(R.string.docs_search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.docs_search_clear))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.idle -> WeMeetEmptyState(
                    title = stringResource(R.string.docs_search_hint),
                    description = stringResource(R.string.docs_search_start),
                    icon = Icons.Outlined.Search,
                )
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
                    hasMore = state.hasMore,
                    loadingMore = state.loadingMore,
                    onLoadMore = vm::loadMore,
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
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
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
        if (hasMore) item {
            androidx.compose.material3.TextButton(onClick = onLoadMore, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.docs_load_more))
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    doc: DocumentDto,
    onClick: () -> Unit,
) {
    DocSearchResultRow(
        title = doc.displayTitle,
        updatedAt = doc.updatedAt,
        folder = doc.isFolder,
        parentTitle = doc.parent?.displayTitle,
        onClick = onClick,
    )
}

/** Document search presentation shared with the host's aggregate search. */
@Composable
fun DocSearchResultRow(
    title: String,
    updatedAt: String?,
    onClick: () -> Unit,
    folder: Boolean = false,
    parentTitle: String? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            DocsFileIcon(folder)
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { stringResource(R.string.docs_untitled) },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(parentTitle?.takeIf { it.isNotBlank() }?.let { parent ->
                    stringResource(R.string.docs_search_in_parent, parent)
                } ?: stringResource(R.string.docs_updated_at, formatIsoTime(updatedAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(start = Dimens.ScreenPadding + Dimens.ListLeadingIcon + Dimens.SpaceM),
            thickness = Dimens.DividerThin, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
