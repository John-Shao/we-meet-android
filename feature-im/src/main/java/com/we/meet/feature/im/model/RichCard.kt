package com.we.meet.feature.im.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息卡片协议 v1(`content_type: rich-card`)—— 与后端 / Web 一致。
 *
 * 群机器人经 webhook 发来的**块级卡片**(后端把飞书 `msg_type=interactive`
 * 规范化成这个形状)。App 端只渲染、不构造。金标准 fixture 见 we-meet 后端仓
 * `core/tests/fixtures/im_cards/rich_card_*.json`,三端共读同一批文件。
 *
 * ## 与 rich-text 的关系
 *
 * **内联 span 是同一套词汇**([RichTextTag] 那三个),只多两个可选布尔
 * `b`/`i`。所以现有 rich-text fixture 一个字节都不用改,rich-card 只新增
 * 块级布局。这里复用 [RichTextTag] 而不是另起一套 —— 另起一套就等着两边漂。
 *
 * ## 两条不能忘的事
 *
 * 1. **按钮永远没有 `value`。** 那是外部服务的私有载荷(可能是 pipeline
 *    token),只存服务端。客户端只拿 `id`。任何时候在 body 里看到 value
 *    都是后端漏了。
 * 2. **转发时要本地剥掉 actions 块**(见 [stripActions])。服务端对转发副本
 *    返回 404 是真正的兜底,但不能让用户看到一排点不动的按钮。
 */

/** header 主题 —— 语义档,不是颜色。各端映射到自己主题已保证过对比度的 token。 */
enum class CardTheme { INFO, SUCCESS, WARNING, DANGER, NEUTRAL }

/** 一个内联 span:复用 rich-text 的三个 tag,text 多两个可选样式位。 */
sealed interface CardSpan {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : CardSpan
    data class Link(val text: String, val href: String) : CardSpan
    data class At(val uid: String, val name: String) : CardSpan
}

enum class CardButtonStyle { DEFAULT, PRIMARY, DANGER }

enum class CardButtonAction { URL, CALLBACK, DOC }

data class CardButton(
    val id: String,
    val text: String,
    val style: CardButtonStyle,
    val action: CardButtonAction,
    /** 只有 [CardButtonAction.URL] 才有。 */
    val url: String = "",
    val docId: String = "",
)

data class CardField(val label: String, val value: String)

sealed interface CardBlock {
    data class Text(val spans: List<CardSpan>) : CardBlock
    data class Fields(val items: List<CardField>) : CardBlock
    data object Divider : CardBlock
    data class Actions(val resolveOnce: Boolean, val buttons: List<CardButton>) : CardBlock
}

data class RichCardBody(
    val headerTitle: String,
    val headerTheme: CardTheme,
    val hasHeader: Boolean,
    val blocks: List<CardBlock>,
    /** 派生投影,只给预览/搜索/@我 检测用 —— **不要渲染它**。 */
    val plain: String,
)

object RichCardParser {

    /** 宽容解析:任何说不通的地方都返回 null,调用方退回纯文本气泡。 */
    fun parse(body: String): RichCardBody? = try {
        val root = JSONObject(body)
        val blocks = mutableListOf<CardBlock>()
        val raw = root.optJSONArray("blocks") ?: JSONArray()
        for (i in 0 until raw.length()) {
            normalizeBlock(raw.optJSONObject(i))?.let(blocks::add)
        }

        var title = ""
        var theme = CardTheme.NEUTRAL
        root.optJSONObject("header")?.let { header ->
            title = header.optString("title")
            theme = themeOf(header.optString("theme"))
        }
        val hasHeader = title.isNotBlank()

        if (blocks.isEmpty() && !hasHeader) {
            null
        } else {
            RichCardBody(
                headerTitle = title,
                headerTheme = theme,
                hasHeader = hasHeader,
                blocks = blocks,
                plain = root.optString("plain"),
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun themeOf(raw: String): CardTheme = when (raw) {
        "info" -> CardTheme.INFO
        "success" -> CardTheme.SUCCESS
        "warning" -> CardTheme.WARNING
        "danger" -> CardTheme.DANGER
        // 认不出的一律 neutral,不是崩 —— 后端加档时老客户端只是少一种配色。
        else -> CardTheme.NEUTRAL
    }

    private fun normalizeBlock(o: JSONObject?): CardBlock? {
        if (o == null) return null
        return when (o.optString("type")) {
            "divider" -> CardBlock.Divider

            "text" -> {
                val spans = mutableListOf<CardSpan>()
                val raw = o.optJSONArray("spans") ?: JSONArray()
                for (i in 0 until raw.length()) {
                    normalizeSpan(raw.optJSONObject(i))?.let(spans::add)
                }
                if (spans.isEmpty()) null else CardBlock.Text(spans)
            }

            "fields" -> {
                val items = mutableListOf<CardField>()
                val raw = o.optJSONArray("items") ?: JSONArray()
                for (i in 0 until raw.length()) {
                    val item = raw.optJSONObject(i) ?: continue
                    val value = item.optString("value")
                    if (value.isNotEmpty()) {
                        items.add(CardField(label = item.optString("label"), value = value))
                    }
                }
                if (items.isEmpty()) null else CardBlock.Fields(items)
            }

            "actions" -> {
                val buttons = mutableListOf<CardButton>()
                val raw = o.optJSONArray("buttons") ?: JSONArray()
                for (i in 0 until raw.length()) {
                    normalizeButton(raw.optJSONObject(i))?.let(buttons::add)
                }
                if (buttons.isEmpty()) null
                else CardBlock.Actions(
                    resolveOnce = o.optString("resolve") != "each",
                    buttons = buttons,
                )
            }

            // 未知块类型丢弃,而不是把 JSON 渲染给人看。
            else -> null
        }
    }

    private fun normalizeSpan(o: JSONObject?): CardSpan? {
        if (o == null) return null
        return when (o.optString("tag")) {
            "text" -> o.optString("text").takeIf { it.isNotEmpty() }?.let {
                CardSpan.Text(it, bold = o.optBoolean("b"), italic = o.optBoolean("i"))
            }
            "a" -> {
                val text = o.optString("text")
                val href = o.optString("href")
                when {
                    text.isEmpty() -> null
                    // 与 rich-text 同一条红线:非 http(s) 的 href 是攻击面不是
                    // 链接。留住字,去掉链接。
                    RichTextParser.isWebUrl(href) -> CardSpan.Link(text, href)
                    else -> CardSpan.Text(text)
                }
            }
            "at" -> {
                val uid = o.optString("uid")
                val name = o.optString("name").ifBlank { uid }
                if (uid.isEmpty() && name.isEmpty()) null else CardSpan.At(uid, name)
            }
            else -> null
        }
    }

    private fun normalizeButton(o: JSONObject?): CardButton? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val text = o.optString("text").takeIf { it.isNotEmpty() } ?: return null
        val style = when (o.optString("style")) {
            "primary" -> CardButtonStyle.PRIMARY
            "danger" -> CardButtonStyle.DANGER
            else -> CardButtonStyle.DEFAULT
        }
        return when (o.optString("action")) {
            "url" -> {
                val url = o.optString("url")
                if (RichTextParser.isWebUrl(url)) {
                    CardButton(id, text, style, CardButtonAction.URL, url)
                } else {
                    null
                }
            }
            "callback" -> CardButton(id, text, style, CardButtonAction.CALLBACK)
            "doc" -> {
                val docId = o.optString("doc_id")
                val url = o.optString("url")
                if (docId.isNotEmpty() && RichTextParser.isWebUrl(url)) {
                    CardButton(id, text, style, CardButtonAction.DOC, url, docId)
                } else {
                    null
                }
            }
            // 认不出的动作类型不渲染 —— 点了没反应的按钮比没有按钮更糟。
            else -> null
        }
    }

    /** 摊平成纯文本 —— 引用、合并转发快照、会话预览都用它。 */
    fun flatten(body: RichCardBody): String {
        if (body.plain.isNotBlank()) return body.plain
        val lines = mutableListOf<String>()
        if (body.hasHeader) lines.add(body.headerTitle)
        for (block in body.blocks) {
            when (block) {
                is CardBlock.Text -> spansPlain(block.spans).trim().takeIf { it.isNotEmpty() }
                    ?.let(lines::add)
                is CardBlock.Fields -> block.items.forEach { item ->
                    listOf(item.label, item.value).filter { it.isNotBlank() }
                        .joinToString(" ")
                        .takeIf { it.isNotEmpty() }
                        ?.let(lines::add)
                }
                // divider / actions 不进纯文本:按钮是控件不是话,进了预览会
                // 读成「构建失败 同意上线 查看日志」,像机器人在念按钮。
                CardBlock.Divider, is CardBlock.Actions -> Unit
            }
        }
        return lines.joinToString(" ").trim()
    }

    fun spansPlain(spans: List<CardSpan>): String = spans.joinToString("") { span ->
        when (span) {
            is CardSpan.Text -> span.text
            is CardSpan.Link -> span.text
            is CardSpan.At -> "@${span.name}"
        }
    }

    /**
     * 直接吃原始 body:什么都拿不到返回空串,调用方自己兜底文案。
     *
     * **必须在 parse 之前短路到 plain** —— jusi 会截断 last_message,截断的
     * JSON 解析不出来,但截断的 plain 仍是人话。
     *
     * 这条注释以前就在,但代码是先 parse 再取 plain —— 于是每张卡在会话列表里
     * 都显示成「[卡片]」。实现见 [RichTextParser.rawPlain]。
     */
    fun preview(body: String): String {
        val short = RichTextParser.rawPlain(body)
        if (short.isNotBlank()) return RichTextParser.squeezePreview(short)
        return parse(body)?.let { RichTextParser.squeezePreview(flatten(it)) }.orEmpty()
    }

    /**
     * 转发副本要剥掉 actions 块。
     *
     * 服务端对转发副本(新 mid、没有按钮记录)返回 404 是真正的兜底,这里是
     * 不让用户看到一排点不动的按钮。**别「顺手」把 actions 也转过去。**
     */
    fun stripActions(body: String): String = try {
        val root = JSONObject(body)
        val blocks = root.optJSONArray("blocks") ?: JSONArray()
        val kept = mutableListOf<Any>()
        var dropped = false
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i)
            if (block?.optString("type") == "actions") dropped = true else kept.add(blocks.get(i))
        }
        // 按钮上面那条 divider 现在什么都不分隔了 —— 只掐**尾部**的,中间的
        // 还在分隔内容。飞书的卡片几乎都是「…内容 / hr / 按钮」这个形状,
        // 不处理的话每张转发过去的卡都会挂一条悬空的线。
        while (kept.isNotEmpty() &&
            (kept.last() as? JSONObject)?.optString("type") == "divider"
        ) {
            kept.removeAt(kept.size - 1)
        }
        if (!dropped) body else root.put("blocks", JSONArray(kept)).toString()
    } catch (_: Throwable) {
        body
    }
}
