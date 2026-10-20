package com.we.meet.feature.assistant.history

import com.we.meet.feature.assistant.aicall.data.AiHistoryApi
import com.we.meet.feature.assistant.aicall.model.AiHistoryConversationDto
import com.we.meet.feature.assistant.aicall.model.AiHistoryMessageDto
import com.we.meet.feature.assistant.aicall.model.AiHistoryUpsertDto
import java.time.Instant

/** Pulls account-owned text history and uploads local call snapshots. */
class AssistantHistorySync(private val store: AssistantHistoryStore, private val api: AiHistoryApi) {
    suspend fun sync(deviceId: String) {
        val remote = api.list().conversations
        store.mergeRemote(remote.mapNotNull(::toEntry))
        for (entry in store.entries.value.filter { it.kind == "call" }) {
            runCatching { api.upsert(toDto(entry, deviceId)) }
        }
    }

    private fun toEntry(conversation: AiHistoryConversationDto): AssistantHistoryEntry? {
        val started = runCatching { Instant.parse(conversation.started_at).toEpochMilli() }.getOrNull() ?: return null
        val ended = conversation.ended_at?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        return AssistantHistoryEntry(
            id = conversation.client_id,
            kind = "call",
            startedAt = started,
            endedAt = ended,
            rows = conversation.messages.map { message ->
                AssistantHistoryRow(
                    id = message.client_id,
                    order = message.position,
                    role = message.role,
                    text = message.text,
                    source = message.source,
                )
            },
            summary = if (conversation.summary.isBlank() && conversation.todos.isEmpty()) null else AssistantSummary(
                summary = conversation.summary,
                decisions = emptyList(),
                tasks = conversation.todos.mapNotNull { todo ->
                    val text = todo["text"] as? String ?: return@mapNotNull null
                    val sourceIds = (todo["source_ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    AssistantTodo(
                        text = text,
                        owner = todo["owner"] as? String ?: "",
                        due = todo["due"] as? String ?: "",
                        sourceIds = sourceIds,
                        done = todo["done"] == true,
                    )
                },
            ),
        )
    }

    private fun toDto(entry: AssistantHistoryEntry, deviceId: String): AiHistoryUpsertDto =
        AiHistoryUpsertDto(
            client_id = entry.id,
            device_id = deviceId,
            kind = "call",
            started_at = Instant.ofEpochMilli(entry.startedAt).toString(),
            ended_at = entry.endedAt?.let { Instant.ofEpochMilli(it).toString() },
            status = if (entry.endedAt == null) "ongoing" else "completed",
            summary = entry.summary?.summary.orEmpty(),
            todos = entry.summary?.tasks?.map { task ->
                mapOf(
                    "text" to task.text,
                    "owner" to task.owner,
                    "due" to task.due,
                    "source_ids" to task.sourceIds,
                    "done" to task.done,
                )
            }.orEmpty(),
            messages = entry.rows.mapIndexed { index, row ->
                AiHistoryMessageDto(
                    client_id = row.id,
                    position = index,
                    role = if (row.role == "user" || row.role == "assistant") row.role else "assistant",
                    text = row.text.take(20_000),
                    source = row.source.take(20),
                )
            },
        )
}
