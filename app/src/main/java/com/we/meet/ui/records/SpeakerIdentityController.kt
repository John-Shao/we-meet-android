package com.we.meet.ui.records

import com.we.meet.data.api.dto.RecordIdentityDecisionRequest
import com.we.meet.data.api.dto.RecordSpeakerContactDto
import com.we.meet.data.api.dto.RecordSpeakerContactPageDto
import com.we.meet.data.api.dto.RecordSpeakerDto
import com.we.meet.data.repository.RecordSourceChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

internal interface SpeakerIdentityOperations {
    suspend fun contacts(query: String?, kind: String, departmentId: String?, offset: Int): Result<RecordSpeakerContactPageDto>
    suspend fun decide(request: RecordIdentityDecisionRequest): Result<RecordSpeakerDto>
}

internal enum class SpeakerIdentityMode { CONTACTS, LABEL }
internal enum class SpeakerIdentityFailure { LOAD, SAVE, CONFLICT, ACCESS }

internal data class SpeakerIdentityState(
    val mode: SpeakerIdentityMode = SpeakerIdentityMode.CONTACTS,
    val query: String = "",
    val search: String = "",
    val kind: String = "all",
    val department: RecordSpeakerContactDto? = null,
    val offset: Int = 0,
    val page: RecordSpeakerContactPageDto = RecordSpeakerContactPageDto(),
    val selected: RecordSpeakerContactDto? = null,
    val label: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val failure: SpeakerIdentityFailure? = null,
) {
    val blocked: Boolean get() = failure in setOf(SpeakerIdentityFailure.CONFLICT, SpeakerIdentityFailure.ACCESS)
    val editable: Boolean get() = !saving && !blocked
    val canSave: Boolean get() = editable && when (mode) {
        SpeakerIdentityMode.CONTACTS -> selected != null && !loading && failure != SpeakerIdentityFailure.LOAD
        SpeakerIdentityMode.LABEL -> cleanSpeakerLabel(label) != null
    }
}

/** One open editor's revision and viewer-bound operations; no disk or saved-state persistence. */
internal class SpeakerIdentityController(
    private val operations: SpeakerIdentityOperations,
    private val revision: Int,
    initialLabel: String = "",
) {
    private val mutable = MutableStateFlow(SpeakerIdentityState(
        mode = if (initialLabel.isEmpty()) SpeakerIdentityMode.CONTACTS else SpeakerIdentityMode.LABEL,
        label = initialLabel,
    ))
    val state = mutable.asStateFlow()
    private var active = true
    private var epoch = 0

    fun close() { active = false; epoch++; mutable.value = SpeakerIdentityState() }

    fun revisionChanged(current: Int) {
        if (active && current != revision) {
            epoch++
            mutable.value = state.value.copy(loading = false, failure = SpeakerIdentityFailure.CONFLICT)
        }
    }

    fun editQuery(value: String) {
        // Keep the old endpoint's UTF-16 search cap; never split a surrogate pair.
        if (active && state.value.editable && value.length <= 80) mutable.value = state.value.copy(query = value)
    }

    fun editLabel(value: String) {
        if (active && state.value.editable && value.codePointCount(0, value.length) <= 256) {
            mutable.value = state.value.copy(label = value, failure = null)
        }
    }

    suspend fun mode(mode: SpeakerIdentityMode) {
        if (!active || !state.value.editable || mode == state.value.mode) return
        epoch++
        mutable.value = state.value.copy(mode = mode, selected = null, loading = false, failure = null)
        if (mode == SpeakerIdentityMode.CONTACTS) reload()
    }

    suspend fun filter(kind: String) {
        if (!active || !state.value.editable || kind !in setOf("all", "member", "external", "departments")) return
        mutable.value = state.value.copy(kind = kind, department = null, offset = 0, selected = null)
        reload()
    }

    suspend fun search() {
        if (!active || !state.value.editable) return
        mutable.value = state.value.copy(search = state.value.query.trim(), offset = 0, selected = null)
        reload()
    }

    suspend fun choose(contact: RecordSpeakerContactDto) {
        if (!active || !state.value.editable || state.value.loading || contact !in state.value.page.results) return
        if (contact.kind == "department") {
            mutable.value = state.value.copy(kind = "member", department = contact, offset = 0,
                query = "", search = "", selected = null)
            reload()
        } else mutable.value = state.value.copy(selected = contact, failure = null)
    }

    suspend fun page(next: Boolean) {
        if (!active || !state.value.editable || state.value.loading) return
        val offset = if (next) state.value.page.nextOffset ?: return else (state.value.offset - 25).coerceAtLeast(0)
        mutable.value = state.value.copy(offset = offset, selected = null)
        reload()
    }

    suspend fun reload() {
        val before = state.value
        if (!active || !before.editable || before.mode != SpeakerIdentityMode.CONTACTS) return
        val request = ++epoch
        mutable.value = before.copy(loading = true, page = RecordSpeakerContactPageDto(), selected = null, failure = null)
        val result = operations.contacts(before.search.ifBlank { null }, before.kind, before.department?.departmentId, before.offset)
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!active || request != epoch) return
        mutable.value = state.value.copy(loading = false, page = result.getOrNull() ?: RecordSpeakerContactPageDto(),
            failure = result.exceptionOrNull()?.let { failure(it, SpeakerIdentityFailure.LOAD) })
    }

    suspend fun save(clear: Boolean = false): Boolean {
        val before = state.value
        if (!active || !before.editable || (!clear && !before.canSave)) return false
        val request = when {
            clear -> RecordIdentityDecisionRequest("clear", revision)
            before.mode == SpeakerIdentityMode.LABEL -> RecordIdentityDecisionRequest("set_label", revision, label = cleanSpeakerLabel(before.label))
            else -> RecordIdentityDecisionRequest("select_contact", revision, contactRef = before.selected?.ref)
        }
        epoch++ // Discard a directory response arriving after the editor submits.
        mutable.value = before.copy(saving = true, loading = false, failure = null)
        val result = operations.decide(request)
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (!active) return false
        val current = state.value
        mutable.value = current.copy(saving = false, failure =
            if (current.blocked) current.failure else result.exceptionOrNull()?.let { failure(it, SpeakerIdentityFailure.SAVE) })
        return result.isSuccess
    }

    private fun failure(error: Throwable, fallback: SpeakerIdentityFailure) = when {
        error is RecordSourceChangedException || error is HttpException && error.code() == 409 -> SpeakerIdentityFailure.CONFLICT
        error is IllegalArgumentException || error is HttpException && error.code() in setOf(401, 403, 404, 410) -> SpeakerIdentityFailure.ACCESS
        else -> fallback
    }
}

internal fun cleanSpeakerLabel(value: String): String? = value.trim().takeIf { text ->
    text.isNotBlank() && text.codePointCount(0, text.length) <= 64 && value.codePoints().noneMatch {
        Character.getType(it) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
    }
}
