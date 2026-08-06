package com.we.meet.feature.im.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `rich-card` 的解析、投影与转发剥离。
 *
 * 金标准 fixture 与 [ImCardContractTest] 读同一个目录(后端仓),协议一动三端
 * 一起红。这里额外验的是**解析层的降级**:坏数据不能把气泡变空,
 * `javascript:` href 不能变成可点的链接。
 */
class RichCardParserTest {

    private val fixtureDir: File by lazy {
        File(
            System.getProperty("imCardFixtures")
                ?: error("imCardFixtures system property missing — see feature-im/build.gradle.kts."),
        )
    }

    private fun load(name: String): String = File(fixtureDir, "$name.json").readText()

    // ---- 金标准 ---------------------------------------------------------------

    @Test
    fun `full card carries header, every span kind, fields, divider and buttons`() {
        val card = RichCardParser.parse(load("rich_card_full"))!!
        assertTrue(card.hasHeader)
        assertEquals("生产构建失败", card.headerTitle)
        assertEquals(CardTheme.DANGER, card.headerTheme)

        assertEquals(
            listOf("Text", "Fields", "Divider", "Actions"),
            card.blocks.map { it::class.simpleName },
        )

        val text = card.blocks[0] as CardBlock.Text
        assertTrue(text.spans.contains(CardSpan.Text("main", bold = true)))
        assertTrue(text.spans.contains(CardSpan.Text("02:14", italic = true)))
        assertTrue(text.spans.contains(CardSpan.At("all", "所有人")))
        assertTrue(text.spans.any { it is CardSpan.Link })
    }

    @Test
    fun `full card has three fields — an odd count, so clients span the last one`() {
        val fields = RichCardParser.parse(load("rich_card_full"))!!
            .blocks.filterIsInstance<CardBlock.Fields>().single()
        assertEquals(3, fields.items.size)
        assertEquals(CardField("环境", "生产"), fields.items[0])
    }

    @Test
    fun `no button ever carries a value — that is the servers private payload`() {
        // 与后端 test_no_button_ever_carries_its_value、Web 同名断言是同一条
        // 不变量的三端。CardButton 里根本没有 value 字段,所以这里验的是
        // fixture 本身没把它漏进 body。
        assertFalse(load("rich_card_full").contains("\"value\": {"))
        val actions = RichCardParser.parse(load("rich_card_full"))!!
            .blocks.filterIsInstance<CardBlock.Actions>().single()
        assertEquals(3, actions.buttons.size)
        assertTrue(actions.resolveOnce)
    }

    @Test
    fun `minimal card parses without a header`() {
        val card = RichCardParser.parse(load("rich_card_minimal"))!!
        assertFalse(card.hasHeader)
        assertEquals(1, card.blocks.size)
    }

    @Test
    fun `degraded card keeps the remaining blocks in order and carries no warnings`() {
        val raw = load("rich_card_degraded")
        val card = RichCardParser.parse(raw)!!
        assertEquals(
            listOf("Text", "Divider", "Text"),
            card.blocks.map { it::class.simpleName },
        )
        assertFalse(raw.contains("warning"))
    }

    // ---- 降级 -----------------------------------------------------------------

    @Test
    fun `malformed input yields null so the caller falls back to plain text`() {
        assertNull(RichCardParser.parse("{ not json"))
        assertNull(RichCardParser.parse("""{"v":1}"""))
        assertNull(RichCardParser.parse("""{"v":1,"blocks":[]}"""))
    }

    @Test
    fun `javascript href keeps the words and drops the link`() {
        val raw = """
            {"v":1,"blocks":[{"type":"text","spans":[
              {"tag":"a","text":"点我","href":"javascript:alert(1)"}]}]}
        """.trimIndent()
        val block = RichCardParser.parse(raw)!!.blocks.single() as CardBlock.Text
        assertEquals(listOf(CardSpan.Text("点我")), block.spans)
    }

    @Test
    fun `unknown block types are dropped, not rendered as JSON`() {
        val raw = """{"v":1,"blocks":[{"type":"chart","data":[1]},{"type":"divider"}]}"""
        assertEquals(listOf(CardBlock.Divider), RichCardParser.parse(raw)!!.blocks)
    }

    @Test
    fun `a button with an unrecognised action is not rendered`() {
        // 点了没反应的按钮比没有按钮更糟。整块因此空掉 → 块被丢 → 无块无 header。
        val raw = """
            {"v":1,"blocks":[{"type":"actions","resolve":"once","buttons":[
              {"id":"b0","text":"x","style":"default","action":"teleport"}]}]}
        """.trimIndent()
        assertNull(RichCardParser.parse(raw))
    }

    @Test
    fun `a url button whose href is not http(s) is not rendered`() {
        val raw = """
            {"v":1,"blocks":[{"type":"actions","resolve":"once","buttons":[
              {"id":"b0","text":"x","style":"default","action":"url","url":"javascript:x"}]}]}
        """.trimIndent()
        assertNull(RichCardParser.parse(raw))
    }

    @Test
    fun `an unknown theme falls back to neutral`() {
        val raw = """{"v":1,"header":{"title":"t","theme":"chartreuse"},"blocks":[{"type":"divider"}]}"""
        assertEquals(CardTheme.NEUTRAL, RichCardParser.parse(raw)!!.headerTheme)
    }

    // ---- 投影 -----------------------------------------------------------------

    @Test
    fun `flatten skips button labels — buttons are controls, not speech`() {
        // plain 若含按钮标签,会话预览会读成「构建失败 同意上线 查看日志」,
        // 像机器人在念按钮。
        val card = RichCardParser.parse(load("rich_card_full"))!!
            .copy(plain = "") // 绕开服务端 plain,验本地摊平的口径
        val flat = RichCardParser.flatten(card)
        assertTrue(flat.contains("生产构建失败"))
        assertTrue(flat.contains("环境 生产"))
        assertFalse(flat.contains("同意上线"))
    }

    @Test
    fun `preview prefers the servers plain projection`() {
        // jusi 把 last_message 截到 200 字:截断的 JSON 解析不出来,截断的
        // plain 仍是人话。短路必须在 parse 之前。
        val raw = """
            {"v":1,"blocks":[{"type":"text","spans":[{"tag":"text","text":"正文"}]}],
             "plain":"服务端给的摘要"}
        """.trimIndent()
        assertEquals("服务端给的摘要", RichCardParser.preview(raw))
    }

    @Test
    fun `preview of unparseable input is blank so the caller supplies the label`() {
        assertEquals("", RichCardParser.preview("{ truncated"))
    }

    // ---- 转发剥离 --------------------------------------------------------------

    @Test
    fun `stripActions removes the buttons and the now-pointless trailing rule`() {
        // 金标准卡片是「text / fields / divider / actions」—— 飞书的卡几乎都
        // 是这个形状。不掐尾部 divider 的话,每张转发过去的卡都挂一条什么都
        // 不分隔的悬空线(真机上就是这么发现的)。
        val stripped = RichCardParser.parse(RichCardParser.stripActions(load("rich_card_full")))!!
        assertEquals(
            listOf("Text", "Fields"),
            stripped.blocks.map { it::class.simpleName },
        )
        assertEquals("生产构建失败", stripped.headerTitle)
        assertNotNull(stripped.plain)
    }

    @Test
    fun `stripActions only trims trailing rules - the ones between content stay`() {
        val raw = """
            {"v":1,"blocks":[
              {"type":"text","spans":[{"tag":"text","text":"上"}]},
              {"type":"divider"},
              {"type":"text","spans":[{"tag":"text","text":"下"}]},
              {"type":"divider"},
              {"type":"actions","resolve":"once","buttons":[]}
            ]}
        """.trimIndent()
        val stripped = RichCardParser.parse(RichCardParser.stripActions(raw))!!
        assertEquals(
            listOf("Text", "Divider", "Text"),
            stripped.blocks.map { it::class.simpleName },
        )
    }

    @Test
    fun `stripActions returns the input untouched when there is nothing to strip`() {
        val raw = load("rich_card_minimal")
        assertEquals(raw, RichCardParser.stripActions(raw))
    }

    @Test
    fun `stripActions never eats the message on malformed input`() {
        assertEquals("{ not json", RichCardParser.stripActions("{ not json"))
    }
}

/**
 * `card-state` 控制消息与 block key 推导(A2)。
 *
 * `actionsBlockKey` 是**三端契约**:服务端 `bot_cards.card_button_defs` 与 Web
 * 的同名函数按同样规则编号,叠加层就是按这个 key 索引的。数错一位的后果是
 * 「点了第二块,结果显示在第一块上」—— 不报错,只诡异。
 */
class CardStateParserTest {

    private val fixtureDir: File by lazy {
        File(
            System.getProperty("imCardFixtures")
                ?: error("imCardFixtures system property missing — see feature-im/build.gradle.kts."),
        )
    }

    private fun load(name: String): String = File(fixtureDir, "$name.json").readText()

    @Test
    fun `parses the golden card-state payload`() {
        val body = CardStateParser.parse(load("rich_card_state"))!!
        assertEquals(717L, body.targetMid)
        assertEquals("a0", body.block)
        assertEquals("b0", body.buttonId)
        assertTrue(body.text.contains("同意上线"))
    }

    @Test
    fun `a payload without a target or block is not a state`() {
        assertNull(CardStateParser.parse("""{"v":1,"block":"a0"}"""))
        assertNull(CardStateParser.parse("""{"v":1,"target_mid":7}"""))
        assertNull(CardStateParser.parse("{ not json"))
    }

    @Test
    fun `the golden card's single actions block is a0`() {
        val blocks = RichCardParser.parse(load("rich_card_full"))!!.blocks
        val index = blocks.indexOfFirst { it is CardBlock.Actions }
        assertEquals("a0", actionsBlockKey(blocks, index))
    }

    @Test
    fun `block keys count actions blocks only — other blocks do not take a number`() {
        val blocks = RichCardParser.parse(
            """
            {"v":1,"blocks":[
              {"type":"actions","resolve":"once","buttons":[
                {"id":"b0","text":"x","style":"default","action":"callback"}]},
              {"type":"divider"},
              {"type":"text","spans":[{"tag":"text","text":"中间"}]},
              {"type":"actions","resolve":"each","buttons":[
                {"id":"b1","text":"y","style":"default","action":"callback"}]}]}
            """.trimIndent(),
        )!!.blocks
        assertEquals("a0", actionsBlockKey(blocks, 0))
        assertEquals("a1", actionsBlockKey(blocks, 3))
    }

    @Test
    fun `card-state routes to its own content type, not Unsupported`() {
        val parsed = MessageContentParser.parse("card-state", load("rich_card_state"))
        assertTrue(parsed is MessageContent.CardState)
    }

    @Test
    fun `card-state is a control type — it must never render as its own row`() {
        assertTrue(MessageContentParser.isControlType("card-state"))
    }
}
