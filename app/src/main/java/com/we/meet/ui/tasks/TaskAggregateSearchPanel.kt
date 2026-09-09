package com.we.meet.ui.tasks

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Reuses task filters and rows while the aggregate screen owns the search field. */
@Composable
fun TaskAggregateSearchPanel(
    vm: TaskViewModel,
    query: String,
    canFilterSelf: Boolean,
    onOpenTask: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val searchKey = "${ui.searchQuery}:${ui.searchFilter}"
    var previousKey by rememberSaveable { mutableStateOf(searchKey) }
    LaunchedEffect(query) { vm.search(query) }
    LaunchedEffect(searchKey) {
        if (previousKey != searchKey) {
            previousKey = searchKey
            listState.scrollToItem(0)
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.retrySearch()
        onPauseOrDispose { }
    }
    TaskSearchPage(
        tasks = ui.searchResults,
        searching = ui.searching,
        failed = ui.searchFailed,
        completed = ui.searchCompleted,
        query = query,
        filter = ui.searchFilter,
        canFilterSelf = canFilterSelf,
        showOverdueMarker = ui.settings.overdueMarkerEnabled,
        onBack = {},
        onQueryChange = vm::search,
        onFilterChange = vm::setSearchFilter,
        onRetry = vm::retrySearch,
        onTaskClick = { onOpenTask(it.id) },
        embedded = true,
        listState = listState,
    )
}
