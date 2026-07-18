package com.we.meet.feature.im.ui.search

/**
 * P1-4 M3(App):AI 问答的事件与引用模型。SSE 客户端在 app 层实现
 * (OkHttp+鉴权),以 `Flow<AskEvent>` provider 注入——feature-im 不反向
 * 依赖 app 模块,与 联系人/会议/文档 provider 同一模式。
 *
 * 事件契约与 Web 一致(设计 §D2):meta{citations,sources} → delta×N →
 * done{citationsUsed, degraded}。LLM 欠费/熔断 = done.degraded,前端转
 * 「检索结果模式」(chips 全可点,§D7)。
 */
data class AskCitation(
    val n: Int,
    /** meeting | im | calendar */
    val kind: String,
    val title: String,
    val snippet: String = "",
    val cid: String? = null,
    val seq: Long? = null,
    val roomId: String? = null,
    val date: String? = null,
)

sealed interface AskEvent {
    data class Meta(
        val citations: List<AskCitation>,
        /** transcripts/im/calendar/summaries → ok|empty|skipped(排障+弱提示)。 */
        val sources: Map<String, String>,
    ) : AskEvent

    data class Delta(val text: String) : AskEvent

    data class Done(
        val citationsUsed: List<Int>,
        val degraded: Boolean,
    ) : AskEvent

    data class Failure(val message: String) : AskEvent
}
