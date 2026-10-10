package com.we.meet.ui.records

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class RecordLink(val recordId: String, val summaryId: String? = null, val summaryView: Boolean = false, val humanId: String? = null)

/** Links are selectors, never permission grants or API destinations. */
object RecordLinks {
    fun material(recordId: String, baseUrl: String, scope: String, summaryId: String? = null, humanId: String? = null): String {
        require(scope in setOf("record", "minutes"))
        require(scope == "minutes" || summaryId == null && humanId == null)
        if (summaryId != null || humanId != null) return share(recordId, baseUrl, summaryId, humanId)
        return share(recordId, baseUrl) + "?tab=" + if (scope == "minutes") "summary" else "overview"
    }
    /**
     * 造一条可分享的记录链接 —— 与 [parse] 共用同一段路径口径(解析器只认
     * `{base}/meeting/records/{uuid}`,这里也只产出这一段)。Web 端「复制记录链接」
     * 复制的就是同一个形状(`{origin}/meeting/records/{id}`),这样同一串链接在
     * Web、App、系统浏览器里都能落到同一条记录。
     */
    fun share(recordId: String, baseUrl: String, summaryId: String? = null, humanId: String? = null): String {
        requireUuid(recordId)
        require(summaryId == null || humanId == null)
        summaryId?.let(::requireUuid)
        humanId?.let(::requireUuid)
        val path = baseUrl.trimEnd('/') + "/meeting/records/" + recordId.lowercase()
        return when {
            humanId != null -> "$path?human=${humanId.lowercase()}"
            summaryId != null -> "$path?summary=${summaryId.lowercase()}"
            else -> path
        }
    }

    fun parse(value: String, baseUrl: String): RecordLink? = runCatching {
        require(value.length <= 4096)
        val uri = URI(value)
        val base = URI(baseUrl)
        require(base.scheme == "https" && uri.scheme == base.scheme)
        require(uri.host != null && uri.host.equals(base.host, ignoreCase = true))
        fun port(input: URI) = if (input.port == -1) 443 else input.port
        require(port(uri) == port(base))
        require(uri.rawUserInfo == null && uri.rawFragment == null)
        val prefix = base.rawPath.orEmpty().trimEnd('/') + "/meeting/records/"
        require(uri.rawPath.startsWith(prefix))
        val record = uri.rawPath.removePrefix(prefix).removeSuffix("/")
        requireUuid(record)
        val params = uri.rawQuery?.split('&')?.map { pair ->
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }.orEmpty()
        require(params.isEmpty() || (params.size == 1 && (params.single().first in setOf("summary", "human") || params.single().first == "tab" && params.single().second in setOf("summary", "overview"))))
        val summary = params.singleOrNull()?.takeIf { it.first == "summary" }?.second
        val human = params.singleOrNull()?.takeIf { it.first == "human" }?.second
        summary?.let(::requireUuid)
        human?.let(::requireUuid)
        RecordLink(record.lowercase(), summary?.lowercase(), params.singleOrNull() == ("tab" to "summary"), human?.lowercase())
    }.getOrNull()

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString().equals(value, ignoreCase = true))
    }
}
