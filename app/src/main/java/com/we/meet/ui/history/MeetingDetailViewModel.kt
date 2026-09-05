package com.we.meet.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.ActionItemDto
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.api.dto.SummaryDto
import com.we.meet.data.api.dto.TranscriptDto
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.repository.MeetingDetailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the 4-tab meeting detail screen. Each tab owns an independent
 * loading state so transcript availability doesn't block the info tab,
 * and the summary's "regenerate" mutation doesn't blank out the others.
 *
 * Mirrors the Web meeting-detail page's loaders one-to-one
 * ([useMeetingRoom], [useMeetingSummary], [useMeetingActionItems],
 * [useMeetingTranscripts]); the Compose UI is the only thing that's
 * different.
 */
class MeetingDetailViewModel(
    application: Application,
    private val repository: MeetingDetailRepository,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    sealed interface LoadState<out T> {
        data object Loading : LoadState<Nothing>
        data class Success<T>(val value: T) : LoadState<T>
        data class Failure(val cause: Throwable) : LoadState<Nothing>
    }

    private val _room = MutableStateFlow<LoadState<RoomDto>>(LoadState.Loading)
    val room: StateFlow<LoadState<RoomDto>> = _room.asStateFlow()

    /**
     * Wrapping LoadState carries Loading/Failure; the inner `SummaryDto?`
     * is null when the backend returned 404 (no summary yet) — distinct
     * from the load itself failing.
     */
    private val _summary = MutableStateFlow<LoadState<SummaryDto?>>(LoadState.Loading)
    val summary: StateFlow<LoadState<SummaryDto?>> = _summary.asStateFlow()

    private val _actionItems = MutableStateFlow<LoadState<List<ActionItemDto>>>(LoadState.Loading)
    val actionItems: StateFlow<LoadState<List<ActionItemDto>>> = _actionItems.asStateFlow()

    private val _transcripts = MutableStateFlow<LoadState<List<TranscriptDto>>>(LoadState.Loading)
    val transcripts: StateFlow<LoadState<List<TranscriptDto>>> = _transcripts.asStateFlow()

    private val _regenerating = MutableStateFlow(false)
    val regenerating: StateFlow<Boolean> = _regenerating.asStateFlow()

    private val displayUsername: String
        get() = tokenStore.nickname?.takeIf { it.isNotBlank() }
            ?: tokenStore.phone
            ?: getApplication<Application>().getString(R.string.default_display_name)

    fun load(idOrSlug: String) {
        loadRoom(idOrSlug)
        loadSummary(idOrSlug)
        loadActionItems(idOrSlug)
        loadTranscripts(idOrSlug)
    }

    fun retryRoom(idOrSlug: String) = loadRoom(idOrSlug)

    fun retrySummary(idOrSlug: String) = loadSummary(idOrSlug)

    fun retryActionItems(idOrSlug: String) = loadActionItems(idOrSlug)

    fun retryTranscripts(idOrSlug: String) = loadTranscripts(idOrSlug)

    private fun loadRoom(idOrSlug: String) {
        viewModelScope.launch {
            _room.value = LoadState.Loading
            repository.getRoom(idOrSlug, displayUsername)
                .onSuccess { _room.value = LoadState.Success(it) }
                .onFailure { _room.value = LoadState.Failure(it) }
        }
    }

    private fun loadSummary(idOrSlug: String) {
        viewModelScope.launch {
            _summary.value = LoadState.Loading
            repository.getSummary(idOrSlug)
                .onSuccess { _summary.value = LoadState.Success(it) }
                .onFailure { _summary.value = LoadState.Failure(it) }
        }
    }

    private fun loadActionItems(idOrSlug: String) {
        viewModelScope.launch {
            _actionItems.value = LoadState.Loading
            repository.getActionItems(idOrSlug)
                .onSuccess { _actionItems.value = LoadState.Success(it) }
                .onFailure { _actionItems.value = LoadState.Failure(it) }
        }
    }

    private fun loadTranscripts(idOrSlug: String) {
        viewModelScope.launch {
            _transcripts.value = LoadState.Loading
            repository.getTranscripts(idOrSlug)
                .onSuccess { _transcripts.value = LoadState.Success(it) }
                .onFailure { _transcripts.value = LoadState.Failure(it) }
        }
    }

    /**
     * Trigger backend regeneration. Refetches summary + action items on
     * acknowledge — the backend's Celery worker may take a few seconds,
     * so a manual second refresh (re-entering the screen) may be needed
     * if the first refetch lands while the task is still pending.
     */
    fun regenerateSummary(idOrSlug: String) {
        if (_regenerating.value) return
        _regenerating.update { true }
        viewModelScope.launch {
            repository.regenerateSummary(idOrSlug)
            loadSummary(idOrSlug)
            loadActionItems(idOrSlug)
            _regenerating.update { false }
        }
    }

    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MeetingDetailViewModel(
                application = app,
                repository = app.meetingDetailRepository,
                tokenStore = app.tokenStore,
            ) as T
    }
}
