package com.we.meet.feature.assistant.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.net.AssistantNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

data class AssistantTodo(val text: String, val owner: String, val due: String,
    @Json(name = "source_ids") val sourceIds: List<String>, val done: Boolean = false)
data class AssistantSummary(val summary: String, val decisions: List<String>, val tasks: List<AssistantTodo>) {
    override fun toString() = "AssistantSummary(<private>)"
    fun export(): String = buildString {
        appendLine(summary)
        decisions.forEach { appendLine("• $it") }
        tasks.forEach { appendLine("[${if (it.done) "x" else " "}] ${it.text} ${it.owner} ${it.due}".trim()) }
    }.trim()

    companion object {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(AssistantSummary::class.java)
    }
}

internal data class SummaryRow(val id: String, val role: String, val text: String, val source: String)
internal data class SummaryRequest(@Json(name = "conversation_id") val conversationId: String,
    val language: String, val rows: List<SummaryRow>)
internal interface AssistantSummaryApi {
    @Headers("Cache-Control: no-store")
    @POST("api/v1.0/assistant-summary/")
    suspend fun generate(@Body body: SummaryRequest): AssistantSummary
}

enum class SummaryRequestState { WORKING, FAILED, TOO_LONG }

/** Request ownership survives rotation; deletion/account changes cannot restore a removed record. */
class AssistantSummaryViewModel(
    private val store: AssistantHistoryStore,
    private val authorized: () -> Boolean,
    private val generate: suspend (AssistantHistoryEntry) -> AssistantSummary,
) : ViewModel() {
    private val mutable = MutableStateFlow<Map<String, SummaryRequestState>>(emptyMap())
    val requests = mutable.asStateFlow()

    fun generate(id: String) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        if (entry.endedAt == null || entry.summary != null || !authorized() || mutable.value[id] == SummaryRequestState.WORKING || entry.rows.none { it.text.isNotBlank() || it.source.isNotBlank() }) return
        mutable.update { it + (id to SummaryRequestState.WORKING) }
        viewModelScope.launch {
            try {
                val result = generate(entry)
                check(authorized())
                val sources = entry.rows.map { it.id }.toSet()
                check(result.summary.isNotBlank() && result.summary.length <= 8000 && result.tasks.size <= 20 && result.decisions.size <= 12)
                check(result.tasks.all { it.text.isNotBlank() && it.sourceIds.isNotEmpty() && sources.containsAll(it.sourceIds) })
                store.saveSummary(id, result)
                kotlinx.coroutines.withTimeout(5000) {
                    store.entries.first { entries -> entries.none { it.id == id } || entries.any { it.id == id && it.summary != null } }
                }
                mutable.update { it - id }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (authorized()) mutable.update { it + (id to if (error is retrofit2.HttpException && error.code() == 400) SummaryRequestState.TOO_LONG else SummaryRequestState.FAILED) }
            }
        }
    }

    class Factory(private val store: AssistantHistoryStore, private val deps: AssistantDeps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AssistantSummaryViewModel::class.java))
            val account = deps.assistantAccount
            val api = AssistantNetwork.retrofit(deps, readTimeoutSeconds = 40).create(AssistantSummaryApi::class.java)
            return AssistantSummaryViewModel(store, { account != null && deps.assistantAccount == account }) { entry ->
                api.generate(SummaryRequest(entry.id, java.util.Locale.getDefault().language.ifBlank { "zh" },
                    entry.rows.filter { it.text.isNotBlank() || it.source.isNotBlank() }.map { SummaryRow(it.id, it.role, it.text, it.source) }))
            } as T
        }
    }
}
