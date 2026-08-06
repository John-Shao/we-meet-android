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

    /**
     * 直接吃原始 body:什么都拿不到就返回空串,调用方自己兜底文案。
     *
     * **短路在 parse 之前** —— 见 [rawPlain]。
     */
    fun preview(body: String): String {
        val short = rawPlain(body)
        if (short.isNotBlank()) return squeezePreview(short)
        return parse(body)?.let { squeezePreview(flatten(it)) }.orEmpty()
    }

    /**
     * 从**原始字符串**里抠出 `plain`,不经过 JSON 解析。
     *
     * 会话列表的 `last_message` 被 jusi 截断过,截断的 JSON 解析不出来 —— 但
     * 截断的 plain 仍然是人话。所以预览的短路必须在 parse **之前**;后端把
     * `plain` 序列化成第一个键也是为了这个(排在最后的话它整段落在截断点之外,
     * 抠也抠不到)。不这么做的后果真机上验到了:会话列表里每张卡都是「[卡片]」。
     *
     * 手写扫描而不是正则:既要正确处理转义,又要能在字符串**中途被截断**时把
     * 已经读到的部分交出来。正则做不到后者。
     */
    fun rawPlain(raw: String): String {
        val at = raw.indexOf("\"plain\"")
        if (at < 0) return ""
        val colon = raw.indexOf(':', at + 7)
        if (colon < 0) return ""
        val open = raw.indexOf('"', colon + 1)
        if (open < 0) return ""

        val out = StringBuilder()
        var i = open + 1
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\') {
                if (ch == '"') break
                out.append(ch)
                i++
                continue
            }
            if (i + 1 >= raw.length) break // 截断刚好落在转义符上
            val next = raw[i + 1]
            if (next == 'u') {
                // 后端用 ensure_ascii=False,中文不走这里;控制字符才会。
                val code = raw.substring(i + 2, minOf(i + 6, raw.length))
                    .toIntOrNull(16)
                out.append(if (code == null) ' ' else code.toChar())
                i += 6
                continue
            }
            // 换行/制表在一行预览里读作空格,其余(\" \\ \/)取字符本身。
            out.append(if (next in "ntr") ' ' else next)
            i += 2
        }
        return out.toString()
    }

    /** 预览统一收口:压掉空白、截到一行放得下的长度。 */
    fun squeezePreview(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(60)
}
