package com.we.meet.ui.records

import com.we.meet.data.api.RecordingImportCandidates
import com.we.meet.data.api.RecordingImportIdentity
import com.we.meet.data.api.dto.VoiceprintPageDto
import com.we.meet.data.api.dto.VoiceprintScopeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface ImportIdentityOperations {
    suspend fun scopes(offset: Int): Result<VoiceprintPageDto<VoiceprintScopeDto>>
    suspend fun candidates(organization: String?, query: String, offset: Int): Result<RecordingImportCandidates>
}
internal data class ImportIdentityState(
    val organization: String? = null,
    val selected: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val scopes: List<VoiceprintScopeDto> = emptyList(),
    val nextScopes: Int? = null,
    val scopeLoading: Boolean = false,
    val scopeFailure: Boolean = false,
    val query: String = "",
    val search: String = "",
    val offset: Int = 0,
    val page: RecordingImportCandidates? = null,
    val loading: Boolean = false,
    val failure: Boolean = false,
) {
    val ready get() = page != null && page.organizationId == organization && !loading && !failure && !scopeFailure
    val intent get() = RecordingImportIdentity(organization, selected.sorted())
}

/** Directory names only live in this open chooser; restored commands contain IDs only. */
internal class ImportIdentityController(private val operations: ImportIdentityOperations, initial: RecordingImportIdentity) {
    private val mutable = MutableStateFlow(ImportIdentityState(organization = initial.organizationId, selected = initial.candidateUserIds.toSet()))
    val state = mutable.asStateFlow()
    private var active = true
    private var visible = true
    private var directoryEpoch = 0
    private var scopesEpoch = 0
    private var frozen = false
    fun freeze(value: Boolean) { frozen = value }
    fun show() { if (active) visible = true }
    fun close() { active = false; visible = false; directoryEpoch++; scopesEpoch++; mutable.value = ImportIdentityState() }
    fun hide() { visible = false; directoryEpoch++; scopesEpoch++; mutable.value = state.value.copy(page = null, names = emptyMap(), scopes = emptyList(), nextScopes = null, loading = false, scopeLoading = false) }
    fun query(value: String) { if (active && !frozen && value.length <= 80) mutable.value = state.value.copy(query = value) }
    fun choose(id: String, limit: Int) {
        val before = state.value
        val page = before.page ?: return
        if (!active || frozen || !before.ready || page.results.none { it.id == id }) return
        if (id !in before.selected && before.selected.size >= limit) return
        val names = page.results.associate { it.id to it.name }
        mutable.value = before.copy(selected = if (id in before.selected) before.selected - id else before.selected + id,
            names = before.names + names)
    }
    fun remove(id: String) { if (active && !frozen) mutable.value = state.value.copy(selected = state.value.selected - id) }
    suspend fun scope(organization: String?) {
        if (!active || frozen || organization == state.value.organization || (organization != null && state.value.scopes.none { it.id == organization && it.policy.enabled })) return
        mutable.value = state.value.copy(organization = organization, selected = emptySet(), names = emptyMap(), page = null, query = "", search = "", offset = 0)
        reload()
    }
    suspend fun search() {
        if (!active || frozen) return
        mutable.value = state.value.copy(search = state.value.query, offset = 0)
        reload()
    }
    suspend fun page(next: Boolean) {
        if (!active || frozen || state.value.loading) return
        val offset = if (next) state.value.page?.nextOffset ?: return else (state.value.offset - 25).coerceAtLeast(0)
        mutable.value = state.value.copy(offset = offset)
        reload()
    }
    suspend fun scopes(more: Boolean = false) {
        if (!active || !visible || (more && (frozen || state.value.scopeLoading))) return
        val offset = if (more) state.value.nextScopes ?: return else 0
        val epoch = ++scopesEpoch
        mutable.value = state.value.copy(scopeLoading = true, scopeFailure = false)
        val result = operations.scopes(offset)
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!active || epoch != scopesEpoch) return
        val page = result.getOrNull()
        val scopes = if (page == null) state.value.scopes else if (more) (state.value.scopes + page.results).distinctBy { it.id }
            else page.results + state.value.scopes.filter { it.id == state.value.organization && page.results.none { fresh -> fresh.id == it.id } }
        mutable.value = state.value.copy(scopes = scopes, nextScopes = page?.nextOffset, scopeLoading = false, scopeFailure = page == null)
    }
    suspend fun reload() {
        if (!active || !visible) return
        val before = state.value
        val epoch = ++directoryEpoch
        mutable.value = before.copy(page = null, loading = true, failure = false)
        val result = operations.candidates(before.organization, before.search, before.offset)
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!active || epoch != directoryEpoch) return
        val page = result.getOrNull()
        mutable.value = state.value.copy(page = page, loading = false, failure = page == null,
            names = state.value.names + page?.results.orEmpty().associate { it.id to it.name })
    }
}
