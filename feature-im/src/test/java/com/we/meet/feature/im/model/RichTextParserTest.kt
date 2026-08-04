package com.we.meet.feature.im.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 富文本解析:宽容降级,尤其是链接的 scheme 白名单。 */
class RichTextParserTest {

    private fun body(content: String, title: String = "", plain: String = ""): String =
        """{"v":1,"title":"$title","content":$content,"plain":"$plain"}"""

    @Test
    fun `keeps text links and mentions`() {
        val parsed = RichTextParser.parse(
            body(
                """[[{"tag":"text","text":"hello "},
                    {"tag":"a","text":"log","href":"https://ci.example.com"},
                    {"tag":"at","uid":"all","name":"所有人"}]]""",
            ),
        )
        assertEquals(3, parsed!!.paragraphs[0].size)
    }

    @Test
    fun `degrades a javascript link to plain text but keeps the words`() {
        // webhook 正文是外部可控的 —— 这是本文件里唯一真正承重的断言。
        val parsed = RichTextParser.parse(
            body("""[[{"tag":"a","text":"点我","href":"javascript:alert(1)"}]]"""),
        )
        assertEquals(RichTextTag.Text("点我"), parsed!!.paragraphs[0][0])
    }

    @Test
    fun `only http and https are link targets`() {
        assertTrue(RichTextParser.isWebUrl("http://x.test"))
        assertTrue(RichTextParser.isWebUrl("https://x.test/a?b=1"))
        assertFalse(RichTextParser.isWebUrl("data:text/html,<script>"))
        assertFalse(RichTextParser.isWebUrl("vbscript:x"))
        assertFalse(RichTextParser.isWebUrl("file:///etc/passwd"))
    }

    @Test
    fun `drops unknown tags and the paragraphs that become empty`() {
        val parsed = RichTextParser.parse(
            body("""[[{"tag":"emotion","emoji_type":"SMILE"}],[{"tag":"text","text":"kept"}]]"""),
        )
        assertEquals(1, parsed!!.paragraphs.size)
        assertEquals(RichTextTag.Text("kept"), parsed.paragraphs[0][0])
    }

    @Test
    fun `returns null for non json`() {
        assertNull(RichTextParser.parse("not json"))
    }

    @Test
    fun `returns null when nothing renderable survives`() {
        assertNull(RichTextParser.parse(body("""[[{"tag":"emotion"}]]""")))
    }

    @Test
    fun `keeps a title only body`() {
        assertEquals("只有标题", RichTextParser.parse(body("[]", title = "只有标题"))!!.title)
    }

    @Test
    fun `flatten prefers the server plain projection`() {
        // jusi 把 last_message 截到 200 字:截断的 JSON 解析不出来,但截断的
        // plain 仍然是人话。
        val parsed = RichTextParser.parse(
            body("""[[{"tag":"text","text":"ignored"}]]""", plain = "server said this"),
        )
        assertEquals("server said this", RichTextParser.flatten(parsed!!))
    }

    @Test
    fun `flatten falls back to walking the paragraphs`() {
        val parsed = RichTextParser.parse(
            body(
                """[[{"tag":"text","text":"分支 main "},
                    {"tag":"a","text":"查看日志","href":"https://x.test"}],
                   [{"tag":"at","uid":"all","name":"所有人"}]]""",
                title = "构建失败",
            ),
        )
        assertEquals("构建失败 分支 main 查看日志 @所有人", RichTextParser.flatten(parsed!!))
    }

    @Test
    fun `preview returns empty for a body it cannot read`() {
        assertEquals("", RichTextParser.preview("{{{"))
    }
}
