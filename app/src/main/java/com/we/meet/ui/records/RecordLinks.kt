package com.we.meet.ui.records

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class RecordLink(val recordId: String, val summaryId: String? = null)

/** Links are selectors, never permission grants or API destinations. */
object RecordLinks {
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
        require(params.isEmpty() || (params.size == 1 && params.single().first == "summary"))
        val summary = params.singleOrNull()?.second
        summary?.let(::requireUuid)
        RecordLink(record.lowercase(), summary?.lowercase())
    }.getOrNull()

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString().equals(value, ignoreCase = true))
    }
}
