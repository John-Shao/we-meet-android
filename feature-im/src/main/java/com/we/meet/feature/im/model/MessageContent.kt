package com.we.meet.feature.im.model

import org.json.JSONObject

/**
 * Parsed message content — the single extension point for new content types.
 * Phase 2 (voice / quote / recall / reaction / merged) adds one subtype here,
 * one branch in [MessageContentParser.parse], and one renderer branch in
 * MessageBubble; nothing else changes.
 */
sealed interface MessageContent {
    data class Text(val body: String) : MessageContent

    /** body = OSS object_key; resolved to a presigned URL at render time. */
    data class Image(val objectKey: String) : MessageContent

    /** body = JSON `{key,name,size}`. */
    data class File(val key: String, val name: String, val size: Long) : MessageContent

    /** Server-injected notices (member joined/left, meeting summary, ...). */
    data class System(val body: String) : MessageContent

    /** Anything this client version doesn't render natively yet. */
    data class Unsupported(val contentType: String, val body: String) : MessageContent
}

object MessageContentParser {

    fun parse(contentType: String, body: String): MessageContent = when (contentType) {
        "text", "" -> MessageContent.Text(body)
        "image" -> MessageContent.Image(body)
        "file" -> parseFile(body)
        "system" -> MessageContent.System(body)
        else -> MessageContent.Unsupported(contentType, body)
    }

    private fun parseFile(body: String): MessageContent = try {
        val json = JSONObject(body)
        MessageContent.File(
            key = json.getString("key"),
            name = json.optString("name", "file"),
            size = json.optLong("size", -1L),
        )
    } catch (_: Throwable) {
        MessageContent.Unsupported("file", body)
    }
}

/** Human-readable byte count for file bubbles (matches web's rendering scale). */
fun formatFileSize(size: Long): String = when {
    size < 0 -> ""
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    else -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}
