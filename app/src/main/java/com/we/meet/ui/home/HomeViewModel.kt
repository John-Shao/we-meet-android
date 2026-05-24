package com.we.meet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.we.meet.WeMeetApp
import com.we.meet.data.history.HistoryEntry
import com.we.meet.data.history.HistoryStore
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel(
    historyStore: HistoryStore,
) : ViewModel() {

    val history: StateFlow<List<HistoryEntry>> = historyStore.entries

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(historyStore = app.historyStore) as T
    }
}
