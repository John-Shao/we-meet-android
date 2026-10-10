package com.we.meet.ui.records

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.IdentityLoginChangedException
import com.we.meet.data.repository.IdentificationSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

internal interface IdentificationOperations {
    fun allowed(): Boolean
    fun pending(): IdentitySubmissionDto?
    fun remember(body: IdentitySubmissionDto)
    fun acknowledge(key: String)
    suspend fun read(key: String?): Result<IdentityResponseDto>
    suspend fun options(revision: Int, offset: Int): Result<IdentityOptionsDto>
    suspend fun people(organization: String?, revision: Int, query: String, offset: Int): Result<IdentityCandidatesDto>
    suspend fun submit(body: IdentitySubmissionDto): Result<IdentityResponseDto>
    suspend fun cancel(key: String, revision: Int): Result<IdentityResponseDto>
    suspend fun decide(speaker: String, suggestion: IdentitySuggestionDto, confirm: Boolean, revision: Int): Result<RecordSpeakerDto>
    fun changed()
    fun stopPreview()
}

internal class SessionIdentificationOperations(private val client: IdentificationSession,
    private val onChanged: () -> Unit, private val onStop: () -> Unit) : IdentificationOperations {
    override fun allowed() = client.allowed()
    override fun pending() = client.pending()
    override fun remember(body: IdentitySubmissionDto) = client.remember(body)
    override fun acknowledge(key: String) = client.acknowledge(key)
    override suspend fun read(key: String?) = client.read(key)
    override suspend fun options(revision: Int, offset: Int) = client.options(revision, offset)
    override suspend fun people(organization: String?, revision: Int, query: String, offset: Int) = client.candidates(organization, revision, query, offset)
    override suspend fun submit(body: IdentitySubmissionDto) = client.submit(body)
    override suspend fun cancel(key: String, revision: Int) = client.cancel(key, revision)
    override suspend fun decide(speaker: String, suggestion: IdentitySuggestionDto, confirm: Boolean, revision: Int) = client.decide(speaker, suggestion, confirm, revision)
    override fun changed() = onChanged()
    override fun stopPreview() = onStop()
}

internal enum class IdentificationFailure { REQUEST, CONFLICT, ACCESS }
internal data class IdentificationState(
    val revision: Int,
    val options: IdentityOptionsDto? = null,
    val scope: String? = null,
    val people: IdentityCandidatesDto? = null,
    val query: String = "", val search: String = "", val offset: Int = 0,
    val selected: Map<String, String> = emptyMap(),
    val targets: Set<String> = emptySet(),
    val labels: Map<String, String> = emptyMap(),
    val response: IdentityResponseDto? = null,
    val retry: IdentitySubmissionDto? = null,
    val busy: Boolean = false, val loadingPeople: Boolean = false,
    val failure: IdentificationFailure? = null,
) {
    val editable get() = !busy && !loadingPeople && failure == null && retry == null
    val canSubmit get() = editable && scope != null && selected.isNotEmpty() && targets.isNotEmpty() && response?.request?.processing != true
    val shouldPoll get() = failure == null && (response?.request?.processing == true || response?.request?.jobs?.any { it.suggestion?.state == "pending" } == true)
}

/** One open, memory-only review; writes require explicit UI actions. */
internal class SpeakerIdentificationController(private val operations: IdentificationOperations, revision: Int) {
    private val mutable = MutableStateFlow(IdentificationState(revision, retry = operations.pending()))
    val state = mutable.asStateFlow()
    private var key = state.value.retry?.requestKey
    private var active = true
    private var epoch = 0
    private fun live() = active && operations.allowed()
    fun close() { active = false; epoch++; operations.stopPreview(); mutable.value = IdentificationState(state.value.revision) }
    private fun adopt(value: IdentityResponseDto) {
        if (!live()) return
        key = value.request?.requestKey
        value.request?.let { operations.acknowledge(it.requestKey) }
        mutable.value = state.value.copy(revision = value.recordRevision, response = value, retry = null, failure = null)
    }
    private fun fail(error: Throwable) {
        operations.stopPreview()
        val status = (error as? HttpException)?.code()
        if (status in setOf(400, 409)) { key?.let(operations::acknowledge); key = null }
        val retry = operations.pending()
        if (retry != null) key = retry.requestKey
        mutable.value = state.value.copy(response = null, people = null, selected = emptyMap(), loadingPeople = false, retry = retry,
            failure = when {
                status == 409 -> IdentificationFailure.CONFLICT
                error is IdentityLoginChangedException || status in setOf(401, 403, 410) || status == 404 && retry == null -> IdentificationFailure.ACCESS
                else -> IdentificationFailure.REQUEST
            })
    }
    private suspend fun run(action: suspend () -> Unit) {
        if (!live() || state.value.busy) return
        epoch++
        mutable.value = state.value.copy(busy = true, loadingPeople = false)
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (live()) fail(error) }
        finally { if (live()) mutable.value = state.value.copy(busy = false) }
    }
    private suspend fun sync() {
        val value = operations.read(key).getOrThrow()
        if (!live()) return
        val options = operations.options(value.recordRevision, 0).getOrThrow()
        if (!live()) return
        val before = state.value
        val previous = before.scope
        var valid = if (previous == "personal") options.personalAllowed else options.scopes.results.any { it.id == previous && it.enabled }
        var scopes = options.scopes
        val oldScope = before.options?.scopes?.results?.find { it.id == previous }
        if (!valid && oldScope?.enabled == true && options.requiredOrganizationId == null) {
            operations.people(previous, value.recordRevision, "", 0).getOrThrow()
            if (!live()) return
            scopes = scopes.copy(results = scopes.results + oldScope)
            valid = true
        }
        val scope = if (valid) previous else if (before.options == null) options.requiredOrganizationId ?: "personal".takeIf { options.personalAllowed } else null
        mutable.value = before.copy(options = options.copy(scopes = scopes), revision = value.recordRevision, scope = scope,
            selected = if (valid) before.selected else emptyMap(), people = null, offset = 0,
            query = if (valid) before.query else "", search = if (valid) before.search else "",
            targets = if (before.options == null) options.targets.map { it.id }.toSet() else before.targets.intersect(options.targets.map { it.id }.toSet()),
            labels = before.labels + options.targets.associate { it.id to it.name })
        adopt(value)
    }
    suspend fun refresh() { run { sync() }; if (live() && state.value.failure == null) loadPeople() }
    suspend fun poll() { if (state.value.shouldPoll && !state.value.loadingPeople) run { adopt(operations.read(key).getOrThrow()) } }
    suspend fun moreScopes() {
        val before = state.value; val next = before.options?.scopes?.nextOffset ?: return
        run {
            val value = operations.options(before.revision, next).getOrThrow()
            if (live()) mutable.value = state.value.copy(options = before.options.copy(scopes = IdentityPageDto(
                (before.options.scopes.results + value.scopes.results).distinctBy { it.id }, value.scopes.nextOffset)))
        }
    }
    suspend fun scope(value: String) {
        if (!live() || state.value.busy || state.value.retry != null || state.value.failure != null || (value != "personal" && state.value.options?.scopes?.results?.none { it.id == value && it.enabled } != false) || (value == "personal" && state.value.options?.personalAllowed != true)) return
        epoch++
        mutable.value = state.value.copy(scope = value, selected = emptyMap(), people = null, offset = 0, search = "", query = "")
        loadPeople()
    }
    fun query(value: String) { if (live() && state.value.editable && value.length <= 80) mutable.value = state.value.copy(query = value) }
    suspend fun search() {
        if (!live() || !state.value.editable) return
        mutable.value = state.value.copy(search = state.value.query.trim(), offset = 0)
        loadPeople()
    }
    suspend fun page(next: Boolean) {
        if (!live() || !state.value.editable) return
        val offset = if (next) state.value.people?.nextOffset ?: return else (state.value.offset - 25).coerceAtLeast(0)
        mutable.value = state.value.copy(offset = offset)
        loadPeople()
    }
    private suspend fun loadPeople() {
        val before = state.value
        if (!live() || before.busy || before.scope == null || before.failure != null) return
        val request = ++epoch
        mutable.value = before.copy(people = null, loadingPeople = true)
        try {
            val value = operations.people(before.scope.takeUnless { it == "personal" }, before.revision, before.search, before.offset).getOrThrow()
            if (live() && request == epoch) mutable.value = state.value.copy(people = value, loadingPeople = false)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (live() && request == epoch) fail(error) }
    }
    fun person(value: IdentityPersonDto, checked: Boolean) {
        val before = state.value
        if (!live() || !before.editable || value !in before.people?.results.orEmpty() || checked && before.selected.size >= 50 && value.id !in before.selected) return
        mutable.value = before.copy(selected = if (checked) before.selected + (value.id to value.name) else before.selected - value.id)
    }
    fun remove(id: String) { if (live() && state.value.editable) mutable.value = state.value.copy(selected = state.value.selected - id) }
    fun target(id: String, checked: Boolean) {
        val before = state.value
        if (!live() || !before.editable || before.options?.targets?.none { it.id == id } != false) return
        mutable.value = before.copy(targets = if (checked) before.targets + id else before.targets - id)
    }
    suspend fun submit(retry: Boolean = false) {
        val before = state.value
        if (!live() || if (retry) before.retry == null || before.busy || before.failure == IdentificationFailure.ACCESS else !before.canSubmit) return
        val body = if (retry) requireNotNull(before.retry) else IdentitySubmissionDto(UUID.randomUUID().toString(), before.revision,
            before.scope.takeUnless { it == "personal" }, before.selected.keys.toList(), before.targets.toList())
        run {
            operations.remember(body); key = body.requestKey
            adopt(operations.submit(body).getOrThrow())
        }
    }
    suspend fun cancel() {
        val current = key ?: return
        if (state.value.failure == IdentificationFailure.ACCESS) return
        run { operations.stopPreview(); adopt(operations.cancel(current, state.value.revision).getOrThrow()) }
    }
    val canCancel get() = key != null && !state.value.busy && state.value.failure != IdentificationFailure.ACCESS
    suspend fun decide(job: IdentityJobDto, confirm: Boolean) {
        val before = state.value; val suggestion = job.suggestion ?: return
        if (!live() || before.busy || before.failure != null || before.response?.request?.processing != false || suggestion.state != "pending" || confirm && !suggestion.canConfirm || job !in before.response.request.jobs) return
        run {
            operations.stopPreview()
            val value = operations.decide(job.speakerId, suggestion, confirm, before.revision).getOrThrow()
            if (!live()) return@run
            mutable.value = state.value.copy(revision = requireNotNull(value.recordRevision), response = null)
            operations.changed()
            sync()
        }
        if (live() && state.value.failure == null) loadPeople()
    }
}
