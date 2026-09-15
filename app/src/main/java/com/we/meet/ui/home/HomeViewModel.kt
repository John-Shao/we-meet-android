package com.we.meet.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.RoomDto
import com.we.meet.data.repository.RoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Classification and ordering come from actual server sessions, across devices. */
class HomeViewModel(application: Application, private val roomRepository: RoomRepository) : AndroidViewModel(application) {
    private val pending = MutableStateFlow<List<RoomDto>>(emptyList())
    private val recent = MutableStateFlow<List<RoomDto>>(emptyList())
    val scheduledMeetings = pending.asStateFlow()
    val recentMeetings = recent.asStateFlow()
    private val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshFailures = failures.asSharedFlow()
    private val mutex = Mutex()
    fun refreshRemoteRooms() {
        viewModelScope.launch {
            mutex.withLock {
                roomRepository.fetchVideoMeetings().onSuccess {
                    pending.value = it.scheduled
                    recent.value = it.recent.take(10)
                }.onFailure {
                    if (it is CancellationException) throw it
                    pending.value = emptyList()
                    recent.value = emptyList()
                    failures.tryEmit(Unit)
                }
            }
        }
    }
    class Factory(private val app: WeMeetApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app, app.roomRepository) as T
    }
}
