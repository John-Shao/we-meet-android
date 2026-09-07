package com.we.meet.feature.docs.ui

/** Matches BlockNote's comment editor.document (blocks, not inline content). */
internal fun commentTextToBlocks(text: String): List<Map<String, Any>> = text.lines().map { line ->
    mapOf(
        "type" to "paragraph",
        "content" to listOf(mapOf("type" to "text", "text" to line, "styles" to emptyMap<String, Any>())),
    )
}

/** Reads Web blocks, nested links, and inline bodies created by early Android builds. */
internal fun commentBodyPlainText(body: Any?): String = when (body) {
    is String -> body
    is Map<*, *> -> body["text"] as? String ?: commentBodyPlainText(body["content"]) +
        (body["children"] as? List<*>)?.takeIf { it.isNotEmpty() }
            ?.let { "\n" + commentBodyPlainText(it) }.orEmpty()
    is List<*> -> body.joinToString(if (body.any { (it as? Map<*, *>)?.get("type") == "paragraph" }) "\n" else "") {
        commentBodyPlainText(it)
    }
    else -> ""
}
