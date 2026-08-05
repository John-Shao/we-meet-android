package com.we.meet.feature.im.model

/**
 * 「我被 @ 了吗」的判定 —— **哪些 content_type 会被扫、怎么扫,只有这里说了算**。
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
