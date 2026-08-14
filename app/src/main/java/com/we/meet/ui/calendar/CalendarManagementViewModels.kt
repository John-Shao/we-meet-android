package com.we.meet.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.WeMeetApp
import com.we.meet.data.api.dto.CalendarMemberDto
import com.we.meet.data.api.dto.CalendarMemberRequest
import com.we.meet.data.api.dto.CalendarShareLinkDto
import com.we.meet.data.api.dto.CalendarSubscriptionRequest
import com.we.meet.data.api.dto.CreateCalendarRequest
import com.we.meet.data.api.dto.UnifiedCalendarDto
import com.we.meet.data.api.dto.UpdateCalendarRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class CalendarManagementUiState(
    val calendars: List<UnifiedCalendarDto> = emptyList(),
    val loading: Boolean = true,
    val unavailable: Boolean = false,
    val error: Boolean = false,
    val busyIds: Set<String> = emptySet(),
)

enum class CalendarManagementAction {
    SHOW_ONLY,
    SHARE,
    COLOR,
    SETTINGS,
    EXPORT,
    UNSUBSCRIBE,
}

data class CalendarManagementGroups(
    val managed: List<UnifiedCalendarDto>,
    val subscribed: List<UnifiedCalendarDto>,
)

fun groupCalendars(calendars: List<UnifiedCalendarDto>): CalendarManagementGroups =
    CalendarManagementGroups(
        managed = calendars.filter { it.capabilities.canManage },
        subscribed = calendars.filterNot { it.capabilities.canManage },
    )

fun isCalendarFormValid(name: String): Boolean = name.trim().isNotEmpty()

internal fun CalendarManagementUiState.afterOptimisticFailure(
    serverSnapshot: List<UnifiedCalendarDto>,
): CalendarManagementUiState = copy(
    calendars = serverSnapshot,
    busyIds = emptySet(),
    error = true,
)

fun actionsForCalendar(calendar: UnifiedCalendarDto): List<CalendarManagementAction> = buildList {
    add(CalendarManagementAction.SHOW_ONLY)
    add(CalendarManagementAction.COLOR)
    if (calendar.capabilities.canShare) add(CalendarManagementAction.SHARE)
    if (calendar.capabilities.canManage) add(CalendarManagementAction.SETTINGS)
    if (calendar.capabilities.canExport) add(CalendarManagementAction.EXPORT)
    if (!calendar.capabilities.canManage) add(CalendarManagementAction.UNSUBSCRIBE)
}

class CalendarManagementViewModel(app: Application) : AndroidViewModel(app) {
    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val _ui = MutableStateFlow(CalendarManagementUiState())
    val ui: StateFlow<CalendarManagementUiState> = _ui.asStateFlow()

    fun refresh() = refreshInternal(preserveError = false)

    private fun refreshInternal(preserveError: Boolean) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    loading = it.calendars.isEmpty(),
                    error = if (preserveError) it.error else false,
                )
            }
            runCatching { api.listCalendars() }
                .onSuccess { rows ->
                    _ui.update { current ->
                        CalendarManagementUiState(
                            calendars = rows,
                            loading = false,
                            error = preserveError && current.error,
                        )
                    }
                }
                .onFailure { failure ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            unavailable = failure is HttpException && failure.code() == 404,
                            error = true,
                        )
                    }
                }
        }
    }

    fun setEnabled(calendar: UnifiedCalendarDto, enabled: Boolean) = mutateOne(
        calendar,
        optimistic = { it.copy(enabled = enabled, subscribed = true) },
    ) {
        api.updateCalendarSubscription(
            calendar.id,
            CalendarSubscriptionRequest(enabled = enabled, color = calendar.color),
        )
    }

    fun setColor(calendar: UnifiedCalendarDto, color: String) = mutateOne(
        calendar,
        optimistic = { it.copy(color = validCalendarColorOrDefault(color), subscribed = true) },
    ) {
        api.updateCalendarSubscription(
            calendar.id,
            CalendarSubscriptionRequest(
                enabled = calendar.enabled,
                color = validCalendarColorOrDefault(color),
            ),
        )
    }

    fun showOnly(calendar: UnifiedCalendarDto) {
        val before = _ui.value.calendars
        _ui.update {
            it.copy(
                calendars = it.calendars.map { row -> row.copy(enabled = row.id == calendar.id) },
                busyIds = it.calendars.mapTo(mutableSetOf()) { row -> row.id },
                error = false,
            )
        }
        viewModelScope.launch {
            runCatching {
                coroutineScope {
                    before.map { row ->
                        async {
                            api.updateCalendarSubscription(
                                row.id,
                                CalendarSubscriptionRequest(
                                    enabled = row.id == calendar.id,
                                    color = row.color,
                                ),
                            )
                        }
                    }.awaitAll()
                }
            }.onSuccess { refresh() }.onFailure {
                _ui.update { state -> state.afterOptimisticFailure(before) }
                refreshInternal(preserveError = true)
            }
        }
    }

    fun unsubscribe(calendar: UnifiedCalendarDto) {
        val before = _ui.value.calendars
        _ui.update {
            it.copy(
                calendars = it.calendars.filterNot { row -> row.id == calendar.id },
                busyIds = it.busyIds + calendar.id,
                error = false,
            )
        }
        viewModelScope.launch {
            runCatching { api.deleteCalendarSubscription(calendar.id) }
                .onSuccess { refresh() }
                .onFailure {
                    _ui.update { state -> state.afterOptimisticFailure(before) }
                    refreshInternal(preserveError = true)
                }
        }
    }

    private fun mutateOne(
        calendar: UnifiedCalendarDto,
        optimistic: (UnifiedCalendarDto) -> UnifiedCalendarDto,
        request: suspend () -> UnifiedCalendarDto,
    ) {
        val before = _ui.value.calendars
        _ui.update {
            it.copy(
                calendars = it.calendars.map { row ->
                    if (row.id == calendar.id) optimistic(row) else row
                },
                busyIds = it.busyIds + calendar.id,
                error = false,
            )
        }
        viewModelScope.launch {
            runCatching { request() }
                .onSuccess { saved ->
                    _ui.update {
                        it.copy(
                            calendars = it.calendars.map { row ->
                                if (row.id == saved.id) saved else row
                            },
                            busyIds = it.busyIds - calendar.id,
                        )
                    }
                }
                .onFailure {
                    _ui.update { state -> state.afterOptimisticFailure(before) }
                    refreshInternal(preserveError = true)
                }
        }
    }
}

enum class CalendarDiscoverTab(val apiValue: String) {
    CONTACTS("contact"),
    ROOMS("room"),
    PUBLIC("public"),
}

data class CalendarDiscoverUiState(
    val tab: CalendarDiscoverTab = CalendarDiscoverTab.CONTACTS,
    val query: String = "",
    val rows: List<UnifiedCalendarDto> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val subscribingIds: Set<String> = emptySet(),
)

class CalendarDiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val _ui = MutableStateFlow(CalendarDiscoverUiState())
    val ui: StateFlow<CalendarDiscoverUiState> = _ui.asStateFlow()
    private var searchJob: Job? = null

    init { scheduleSearch(immediate = true) }

    fun setTab(tab: CalendarDiscoverTab) {
        if (_ui.value.tab == tab) return
        _ui.update { it.copy(tab = tab, rows = emptyList()) }
        scheduleSearch(immediate = true)
    }

    fun setQuery(query: String) {
        _ui.update { it.copy(query = query) }
        scheduleSearch(immediate = false)
    }

    fun retry() = scheduleSearch(immediate = true)

    fun subscribe(calendar: UnifiedCalendarDto) {
        if (calendar.id in _ui.value.subscribingIds || calendar.subscribed) return
        _ui.update { it.copy(subscribingIds = it.subscribingIds + calendar.id, error = false) }
        viewModelScope.launch {
            runCatching {
                api.updateCalendarSubscription(
                    calendar.id,
                    CalendarSubscriptionRequest(enabled = true, color = calendar.color),
                )
            }.onSuccess { saved ->
                _ui.update {
                    it.copy(
                        rows = it.rows.map { row -> if (row.id == saved.id) saved else row },
                        subscribingIds = it.subscribingIds - calendar.id,
                    )
                }
            }.onFailure {
                _ui.update { state ->
                    state.copy(subscribingIds = state.subscribingIds - calendar.id, error = true)
                }
            }
        }
    }

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(CALENDAR_DISCOVER_DEBOUNCE_MS)
            val snapshot = _ui.value
            _ui.update { it.copy(loading = true, error = false) }
            try {
                val rows = api.discoverCalendars(snapshot.tab.apiValue, snapshot.query.trim())
                if (_ui.value.tab == snapshot.tab && _ui.value.query == snapshot.query) {
                    _ui.update { it.copy(rows = rows, loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (_ui.value.tab == snapshot.tab && _ui.value.query == snapshot.query) {
                    _ui.update { it.copy(loading = false, error = true) }
                }
            }
        }
    }
}

internal const val CALENDAR_DISCOVER_DEBOUNCE_MS = 250L

data class CalendarEditorUiState(
    val calendar: UnifiedCalendarDto? = null,
    val members: List<CalendarMemberDto> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false,
    val completed: Boolean = false,
)

class CalendarEditorViewModel(
    app: Application,
    private val calendarId: String?,
) : AndroidViewModel(app) {
    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val _ui = MutableStateFlow(CalendarEditorUiState(loading = calendarId != null))
    val ui: StateFlow<CalendarEditorUiState> = _ui.asStateFlow()

    init { if (calendarId != null) load() }

    fun load() {
        val id = calendarId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = false) }
            runCatching {
                val calendar = api.getCalendar(id)
                val members = if (calendar.capabilities.canManage) {
                    api.listCalendarMembers(id)
                } else emptyList()
                calendar to members
            }.onSuccess { (calendar, members) ->
                _ui.value = CalendarEditorUiState(calendar = calendar, members = members)
            }.onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    fun create(
        name: String,
        description: String,
        color: String,
        organizationAccess: String,
        members: List<CalendarMemberRequest>,
    ) {
        if (_ui.value.saving || !isCalendarFormValid(name)) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = false) }
            runCatching {
                api.createCalendar(
                    CreateCalendarRequest(
                        name = name.trim(),
                        description = description.trim(),
                        color = validCalendarColorOrDefault(color),
                        organizationDefaultAccess = organizationAccess,
                        members = members,
                    ),
                )
            }.onSuccess { saved ->
                _ui.update { it.copy(calendar = saved, saving = false, completed = true) }
            }.onFailure { _ui.update { it.copy(saving = false, error = true) } }
        }
    }

    fun save(name: String, description: String, access: String, color: String) {
        val current = _ui.value.calendar ?: return
        if (_ui.value.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = false) }
            runCatching {
                val updated = api.updateCalendar(
                    current.id,
                    UpdateCalendarRequest(
                        name = name.trim().takeIf { current.kind == "shared" },
                        description = description.trim(),
                        organizationDefaultAccess = access,
                    ),
                )
                val subscription = api.updateCalendarSubscription(
                    current.id,
                    CalendarSubscriptionRequest(
                        enabled = current.enabled,
                        color = validCalendarColorOrDefault(color),
                    ),
                )
                updated.copy(
                    color = subscription.color,
                    enabled = subscription.enabled,
                    subscribed = subscription.subscribed,
                )
            }.onSuccess { saved ->
                _ui.update { it.copy(calendar = saved, saving = false, completed = true) }
            }.onFailure { _ui.update { it.copy(saving = false, error = true) } }
        }
    }

    fun addMember(userId: String, role: String) {
        val id = calendarId ?: return
        viewModelScope.launch {
            runCatching { api.addCalendarMember(id, CalendarMemberRequest(userId, role)) }
                .onSuccess { saved -> _ui.update { it.copy(members = it.members + saved) } }
                .onFailure { _ui.update { it.copy(error = true) } }
        }
    }

    fun updateMember(member: CalendarMemberDto, role: String) {
        val id = calendarId ?: return
        viewModelScope.launch {
            runCatching { api.updateCalendarMember(id, member.id, mapOf("role" to role)) }
                .onSuccess { saved ->
                    _ui.update {
                        it.copy(members = it.members.map { row -> if (row.id == saved.id) saved else row })
                    }
                }
                .onFailure { _ui.update { it.copy(error = true) } }
        }
    }

    fun removeMember(member: CalendarMemberDto) {
        val id = calendarId ?: return
        viewModelScope.launch {
            runCatching { api.deleteCalendarMember(id, member.id) }
                .onSuccess {
                    _ui.update { it.copy(members = it.members.filterNot { row -> row.id == member.id }) }
                }
                .onFailure { _ui.update { it.copy(error = true) } }
        }
    }

    fun deleteCalendar() {
        val current = _ui.value.calendar ?: return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = false) }
            runCatching { api.deleteCalendar(current.id) }
                .onSuccess { _ui.update { it.copy(saving = false, completed = true) } }
                .onFailure { _ui.update { it.copy(saving = false, error = true) } }
        }
    }

    class Factory(private val app: Application, private val calendarId: String?) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarEditorViewModel(app, calendarId) as T
    }
}

data class CalendarOwnerShareUiState(
    val calendar: UnifiedCalendarDto? = null,
    val link: CalendarShareLinkDto? = null,
    val loading: Boolean = true,
    val resetting: Boolean = false,
    val error: Boolean = false,
)

class CalendarOwnerShareViewModel(app: Application, private val calendarId: String) :
    AndroidViewModel(app) {
    private val api = (app as WeMeetApp).apiClient.calendarApi
    private val _ui = MutableStateFlow(CalendarOwnerShareUiState())
    val ui: StateFlow<CalendarOwnerShareUiState> = _ui.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = false) }
            runCatching {
                coroutineScope {
                    val calendar = async { api.getCalendar(calendarId) }
                    val link = async { api.getCalendarShareLink(calendarId) }
                    calendar.await() to link.await()
                }
            }.onSuccess { (calendar, link) ->
                _ui.value = CalendarOwnerShareUiState(calendar = calendar, link = link, loading = false)
            }.onFailure { _ui.update { it.copy(loading = false, error = true) } }
        }
    }

    fun resetLink() {
        if (_ui.value.resetting) return
        viewModelScope.launch {
            _ui.update { it.copy(resetting = true, error = false) }
            runCatching { api.resetCalendarShareLink(calendarId) }
                .onSuccess { link -> _ui.update { it.copy(link = link, resetting = false) } }
                .onFailure { _ui.update { it.copy(resetting = false, error = true) } }
        }
    }

    class Factory(private val app: Application, private val calendarId: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarOwnerShareViewModel(app, calendarId) as T
    }
}
