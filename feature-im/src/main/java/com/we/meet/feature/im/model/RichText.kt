package com.we.meet.feature.im.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 富文本消息协议 v1(`content_type: rich-text`)—— 与后端 / Web 一致。
 *
 * 只有群机器人产生这种消息(后端把飞书的 `msg_type=post` 规范化成这个形状),
 * App 端只渲染、不构造。金标准 fixture 见 we-meet 后端仓
 * `core/tests/fixtures/im_cards/rich_text_*.json`,三端共读同一批文件。
 *
 * 刻意是单语言的:飞书的 post 带 `{zh_cn, en_us}` 外壳,但同一条 IM 消息不该
 * 按接收方的 locale 变形,后端在 webhook 入口就拍平了。
 *
 * 单独一个文件而不是塞进 [MessageContent]:那个文件是「分发表」,已经 277 行。
 * 但分发入口仍然只有 `MessageContentParser.parse` 一处。
 */
sealed interface RichTextTag {
    data class Text(val text: String) : RichTextTag
    data class Link(val text: String, val href: String) : RichTextTag
    data class At(val uid: String, val name: String) : RichTextTag
}

/** 一段富文本:标题(可空)+ 若干段落,每段是一串内联片段。 */
data class RichTextBody(
    val title: String,
    val paragraphs: List<List<RichTextTag>>,
    /** 派生投影,只给预览/搜索/@我 检测用 —— **不要渲染它**。 */
    val plain: String,
)

object RichTextParser {

    /**
     * 宽容解析:任何说不通的地方都返回 null,调用方退回纯文本气泡。
     *
     * 未知 tag 直接丢弃,空段落收掉;标题和段落都空 → null。
     */
    fun parse(body: String): RichTextBody? = try {
        val root = JSONObject(body)
        val title = root.optString("title")
        val paragraphs = mutableListOf<List<RichTextTag>>()
        val content = root.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val raw = content.optJSONArray(i) ?: continue
            val tags = mutableListOf<RichTextTag>()
            for (j in 0 until raw.length()) {
                normalizeTag(raw.optJSONObject(j))?.let(tags::add)
            }
            if (tags.isNotEmpty()) paragraphs.add(tags)
        }
        if (paragraphs.isEmpty() && title.isBlank()) {
            null
        } else {
            RichTextBody(
                title = title,
                paragraphs = paragraphs,
                plain = root.optString("plain"),
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun normalizeTag(o: JSONObject?): RichTextTag? {
        if (o == null) return null
        return when (o.optString("tag")) {
            "text" -> o.optString("text").takeIf { it.isNotEmpty() }?.let(RichTextTag::Text)
            "a" -> {
                val text = o.optString("text")
                val href = o.optString("href")
                when {
                    text.isEmpty() -> null
                    // 只放行 http(s):webhook 正文是外部可控的,一条
                    // `javascript:` href 就是一个可点的攻击面。非法 scheme
                    // 不丢内容,留住字、去掉链接。
                    isWebUrl(href) -> RichTextTag.Link(text, href)
                    else -> RichTextTag.Text(text)
                }
            }
            "at" -> {
                val uid = o.optString("uid")
                val name = o.optString("name").ifBlank { uid }
                if (uid.isEmpty() && name.isEmpty()) null else RichTextTag.At(uid, name)
            }
            // 未知 tag 丢弃,而不是把 JSON 渲染给人看。
            else -> null
        }
    }

    fun isWebUrl(href: String): Boolean {
        val lower = href.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** 摊平成纯文本 —— 引用、合并转发快照、会话预览都用它。 */
    fun flatten(body: RichTextBody): String {
        if (body.plain.isNotBlank()) return body.plain
        val lines = mutableListOf<String>()
        if (body.title.isNotBlank()) lines.add(body.title)
        for (paragraph in body.paragraphs) {
            val line = paragraph.joinToString("") { tag ->
                when (tag) {
                    is RichTextTag.Text -> tag.text
                    is RichTextTag.Link -> tag.text
                    is RichTextTag.At -> "@${tag.name}"
                }
            }.trim()
            if (line.isNotEmpty()) lines.add(line)
        }
        return lines.joinToString(" ").trim()
    }

    /** 直接吃原始 body:解析不出来就返回空串,调用方自己兜底文案。 */
    fun preview(body: String): String = parse(body)?.let(::flatten).orEmpty()
}
