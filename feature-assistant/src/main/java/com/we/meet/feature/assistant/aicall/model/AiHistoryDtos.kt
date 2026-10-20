package com.we.meet.feature.assistant.aicall.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiHistoryMessageDto(
    val client_id: String,
    val position: Int,
    val role: String,
    val text: String,
    val source: String = "",
)

@JsonClass(generateAdapter = true)
data class AiHistoryConversationDto(
    val id: String,
    val client_id: String,
    val started_at: String,
    val ended_at: String? = null,
    val summary: String = "",
    val todos: List<Map<String, Any?>> = emptyList(),
    val messages: List<AiHistoryMessageDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AiHistoryListDto(
    val conversations: List<AiHistoryConversationDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AiHistoryUpsertDto(
    val client_id: String,
    val device_id: String,
    val kind: String = "call",
    val title: String = "",
    val started_at: String,
    val ended_at: String? = null,
    val status: String = "completed",
    val summary: String = "",
    val todos: List<Map<String, Any?>> = emptyList(),
    val messages: List<AiHistoryMessageDto> = emptyList(),
)
