package com.we.meet.feature.assistant.aicall.model

import com.we.meet.feature.assistant.history.AssistantHistoryRow

/** Whole final utterances only; never infer a command from a substring or an AI reply. */
internal object CallGoodbye {
    private val greeting = Regex(
        "(?:好|好的|好了|好啦)?" + // i18n-exempt: recognized utterances, not display strings.
            "(?:那|那就|那先这样|先这样|我们下次聊|下次聊|今天先到这里|今天先聊到这里)?" + // i18n-exempt: speech grammar.
            "(?:再见|再見|拜拜)(?:了|啦|吧|了吧|了哈|哈|呀|哦|咯|喽|了哦)?", // i18n-exempt: speech grammar.
    )
    private val pauses = setOf('，', ',', '。', '.', '！', '!', '～', '~', '…')

    fun matches(text: String): Boolean {
        if (text.length > 96) return false
        // Quotes and question marks deliberately remain, so quoted/queried goodbyes
        // cannot become a bare command after punctuation normalization.
        val normalized = text.filterNot { it.isWhitespace() || it in pauses }
        return greeting.matches(normalized)
    }

    fun shouldEndCall(state: AiCallUiState, sessionId: String, row: AssistantHistoryRow, currentClient: Boolean): Boolean =
        currentClient && state.transcriptSessionId == sessionId && state.status is AiCallStatus.Active &&
            row.role == "user" && !row.isStreaming && row.photo == null && matches(row.text)
}
