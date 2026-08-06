package com.we.meet.feature.im.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * @ 的口径 —— **哪些 content_type 会被扫、怎么扫,只有这里说了算**;转发时怎么
 * 把 @ 拆掉([defuseMentions])也在这里,两者必须同进同退:判定多认一条腿而拆
 * 的时候漏了它,就是「转发一张卡把全群又 @ 一遍」。
 *
 * ## 为什么要有别名表
 *
 * 这个判定必须与**消息是用哪个 locale 发出来的**无关。德语同事在输入框里选
 * 「@Alle」发出去,消息正文里就是 `@Alle`;而中文同事的客户端只拿自己 locale
 * 的 `@所有人` 去比,永远对不上 —— 没有报错、没有日志,就是收不到提醒。
 *
 * 治不了的那一半在服务端:`client.sendText()` 直连 jusi 不过 we-meet 后端,
 * **没有任何服务端归一化点**。所以只能让每个客户端多认几个字面量。
 *
 * [MENTION_EVERYONE_ALIASES] 与后端
 * `core/tests/fixtures/im_cards/mention_everyone_aliases.json` 同源,三端各存
 * 一份硬编码,契约测试(`MentionAliasesTest`)断言 常量 == 文件 == 各 locale 的
 * `strings.xml` 里的 `im_mention_everyone`。与色板下标、rich-text fixture
 * 完全同一手法。**改动要三仓同批。**
 *
 * ## 已知局限(不是本次引入的)
 *
 * 子串匹配没有词边界,所以 `@Allen` 会命中 `@Alle`。CJK 没有词边界,三端统一
 * 加边界判定代价过高,维持既有口径。
 */
val MENTION_EVERYONE_ALIASES: List<String> = listOf(
    // i18n-exempt: 不是给用户看的文案,是一张**全语种同时成立**的匹配表 ——
    // 搬进 strings.xml 就只剩当前 locale 那一条,正好毁掉它存在的理由。
    "所有人", // i18n-exempt: 见上
    "Everyone",
    "Alle",
    "Tout le monde",
    "Iedereen",
)

/** [mentionScan] 的结果:这条消息点到我了 / 点了所有人。 */
data class MentionHit(
    val self: Boolean = false,
    val everyone: Boolean = false,
)

/** 这段文本里有没有「@所有人」—— 任意语种、大小写无关。 */
fun mentionsEveryone(text: String): Boolean {
    val haystack = text.lowercase()
    return MENTION_EVERYONE_ALIASES.any { haystack.contains("@${it.lowercase()}") }
}

/** `at` 标签里代表「所有人」的 uid。后端在 webhook 入口就归一了大小写。 */
private const val AT_EVERYONE_UID = "all"

/**
 * 扫一条入站消息。[selfNames] 是「我」的所有叫法。
 *
 * - `text` —— 人手输入,只有字面量可扫。
 * - `rich-text` —— 群机器人发的,带结构,所以 @所有人 走**结构判定**
 *   (`at` 标签 uid == `all`);字面量那一路保留,机器人完全可以在正文里直接
 *   打「@所有人」而不发 `at` 标签。
 *   但**点名到人只走 `plain` 投影,刻意不看 `at.uid`**:那是 webhook 发送方
 *   随手填的外部字符串,不是我们的 im uid,拿它跟自己比既不对,还等于开了个
 *   「猜中 uid 就能定向戳人」的口子。
 * - `quote` —— **只扫回复正文,不扫被引用的快照**。引用一条 @所有人 的消息
 *   会让所有人**再被通知一次**,那是 bug 不是设计。
 * - 其余(图片/文件/卡片/合并转发/控制消息……)一律不扫。
 */
fun mentionScan(
    contentType: String,
    body: String,
    selfNames: List<String?>,
): MentionHit {
    fun scanLiteral(text: String) = MentionHit(
        self = selfNames.any { !it.isNullOrBlank() && text.contains("@$it") },
        everyone = mentionsEveryone(text),
    )

    return when (contentType) {
        "text" -> scanLiteral(body)

        "rich-text" -> {
            val rich = RichTextParser.parse(body) ?: return MentionHit()
            val byTag = rich.paragraphs.any { para ->
                para.any { it is RichTextTag.At && it.uid == AT_EVERYONE_UID }
            }
            val byPlain = scanLiteral(rich.plain)
            MentionHit(self = byPlain.self, everyone = byTag || byPlain.everyone)
        }

        "rich-card" -> {
            // 与 rich-text 同一套判定:@所有人 走结构(span 词汇是共用的),
            // 点名到人只走 plain。卡片的 spans 分散在各块里,先摊平再判。
            val card = RichCardParser.parse(body) ?: return MentionHit()
            val byTag = card.blocks.any { block ->
                block is CardBlock.Text &&
                    block.spans.any { it is CardSpan.At && it.uid == AT_EVERYONE_UID }
            }
            val byPlain = scanLiteral(card.plain)
            MentionHit(self = byPlain.self, everyone = byTag || byPlain.everyone)
        }

        "quote" ->
            when (val parsed = MessageContentParser.parse(contentType, body)) {
                is MessageContent.Quote -> scanLiteral(parsed.text)
                else -> MentionHit()
            }

        else -> MentionHit()
    }
}

// ---- 转发时拆掉 @ ------------------------------------------------------------

/** `@所有人` → `所有人`。与 [mentionsEveryone] 同一张表、同样大小写无关。 */
private val EVERYONE_AT = Regex(
    "@(" + MENTION_EVERYONE_ALIASES.joinToString("|") { Regex.escape(it) } + ")",
    RegexOption.IGNORE_CASE,
)

/** 只摘 `@` 前缀,字一个不删 —— 预览里读作「…运行日志 所有人 环境 生产」。 */
private fun unmark(plain: String, atNames: List<String>): String {
    var out = EVERYONE_AT.replace(plain) { it.groupValues[1] }
    // 点名到人:只摘这条消息自己 `at` 标签里出现过的名字,不碰正文里别的 `@`。
    for (name in atNames) if (name.isNotEmpty()) out = out.replace("@$name", name)
    return out
}

/**
 * 把要**转发**出去的 body 里的 @ 拆掉,让它在目标会话里不再点亮任何人。
 *
 * 转发的人想 @ 全群,应该自己打 —— 而不是靠转发时夹带。飞书也是这个口径:
 * 转发过去的 @ 退化成普通文字,不触发通知。
 *
 * ## 为什么正文保留 `@`、只把 `plain` 里的摘掉
 *
 * [mentionScan] 有两条腿:结构(`at` 标签)和 `plain` 里的字面量。**两条都得断**,
 * 只断一条等于没断。但正文是「机器人当时说了什么」,读者该照原样看到 —— 所以
 * 正文只把 `at` 标签降级成普通文字(渲染上从高亮变成正文色,这正是「这个 @ 不
 * 生效」的视觉信号),文字一个字不改;真正被改掉的是 `plain` 投影里那个 `@`。
 * 这层刻意的不一致只在预览/搜索里看得见,换来的是正文不被篡改。
 *
 * ## 拆不掉的那一半(刻意不做)
 *
 * 纯 `text` 消息不碰:那是**人写的一句话**,body 就是正文、没有投影层可改,
 * 动它等于替人改口。同理机器人在正文里手打的「@张三」—— 纯文本里的人名与普通
 * 文字无从区分。所以转发一条纯文本的 `@所有人` 仍然会亮,这条是已知边界。
 *
 * 这里直接改 JSON 而不是走数据类:两个 parser 都是单向的(App 端只渲染不构造),
 * 为拆个 @ 造一套 builder 反而多一份会漂的协议实现。手法同
 * [RichCardParser.stripActions]。
 */
fun defuseMentions(contentType: String, body: String): String = try {
    when (contentType) {
        "rich-card" -> defuseCard(body)
        "rich-text" -> defuseRichText(body)
        else -> body
    }
} catch (_: Throwable) {
    body
}

private fun defuseCard(body: String): String {
    val root = JSONObject(body)
    // plain 从**原始** spans 推,推完一定要写回去 —— 不写的话对端拿不到 plain
    // 会照降级后的正文重推一遍,`@所有人` 原样长回来。
    val derived = root.optString("plain").ifBlank {
        RichCardParser.parse(body)?.let(RichCardParser::flatten).orEmpty()
    }
    val names = mutableListOf<String>()
    val blocks = root.optJSONArray("blocks") ?: JSONArray()
    for (i in 0 until blocks.length()) {
        val block = blocks.optJSONObject(i) ?: continue
        if (block.optString("type") != "text") continue
        val spans = block.optJSONArray("spans") ?: continue
        for (j in 0 until spans.length()) {
            val span = spans.optJSONObject(j) ?: continue
            if (span.optString("tag") != "at") continue
            val name = span.optString("name").ifBlank { span.optString("uid") }
            names.add(name)
            spans.put(j, JSONObject().put("tag", "text").put("text", "@$name"))
        }
    }
    val plain = unmark(derived, names)
    if (names.isEmpty() && plain == derived) return body
    return root.put("plain", plain).toString()
}

private fun defuseRichText(body: String): String {
    val root = JSONObject(body)
    val derived = root.optString("plain").ifBlank {
        RichTextParser.parse(body)?.let(RichTextParser::flatten).orEmpty()
    }
    val names = mutableListOf<String>()
    val content = root.optJSONArray("content") ?: JSONArray()
    for (i in 0 until content.length()) {
        val paragraph = content.optJSONArray(i) ?: continue
        for (j in 0 until paragraph.length()) {
            val tag = paragraph.optJSONObject(j) ?: continue
            if (tag.optString("tag") != "at") continue
            val name = tag.optString("name").ifBlank { tag.optString("uid") }
            names.add(name)
            paragraph.put(j, JSONObject().put("tag", "text").put("text", "@$name"))
        }
    }
    val plain = unmark(derived, names)
    if (names.isEmpty() && plain == derived) return body
    return root.put("plain", plain).toString()
}
