package com.we.meet.data.chat

data class ChatMessageUi(
    val id: String,
    val senderIdentity: String,
    val senderName: String,
    val isLocal: Boolean,
    val isHost: Boolean,
    val text: String,
    val timestampMs: Long,
)
