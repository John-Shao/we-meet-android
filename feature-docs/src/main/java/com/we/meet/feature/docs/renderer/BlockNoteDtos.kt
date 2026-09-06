package com.we.meet.feature.docs.renderer

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * BlockNote JSON wire shapes (设计文档 §4.5 渲染器输入).
 *
 * 解析是**宽容的**:BlockNote 的 block/inline 集合随版本演进,所有字段可缺省、
 * 未知 type 不崩 —— 渲染层对未知块/行内一律降级为占位,见 BlockNoteRenderer。
 */
@JsonClass(generateAdapter = true)
data class JsonBlockDto(
    val id: String? = null,
    val type: String = "",
    val props: Map<String, Any?> = emptyMap(),
    /** inline list — except `table`, whose content is tableContent. */
    val content: Any? = null,
    val children: List<JsonBlockDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class JsonInlineDto(
    val type: String = "",
    val text: String? = null,
    val styles: Map<String, Any?> = emptyMap(),
    val href: String? = null,
    val props: Map<String, Any?> = emptyMap(),
    val content: Any? = null,
)

@JsonClass(generateAdapter = true)
data class JsonTableContentDto(
    val type: String = "",
    val rows: List<JsonTableRowDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class JsonTableRowDto(
    val cells: List<List<JsonInlineDto>> = emptyList(),
)

// ---- helpers over the flexible maps ----

/** props/values that arrive as Moshi primitives. */
fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

fun Map<String, Any?>.bool(key: String): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> false
    }

fun Map<String, Any?>.int(key: String): Int? =
    when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
