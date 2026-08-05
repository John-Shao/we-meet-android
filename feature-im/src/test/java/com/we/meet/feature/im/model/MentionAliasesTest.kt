package com.we.meet.feature.im.model

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@所有人` 别名表的三方契约:**常量 == 后端 fixture == 各 locale 资源**。
 *
 * 三者任意一处漂了这里就红。最要紧的是第三条:有人加一个语种、或者把
 * 「所有人」改个说法,如果没同步别名表,那个语种的用户被 @所有人 时**不会亮**
 * —— 静默失败,没有报错、没有日志,不会有人报障。
 *
 * fixture 与 [ImCardContractTest] 读的是同一个目录(后端仓);locale 资源直接
 * 读本仓各 values 目录下的 `strings.xml`,因为 JVM 单测拿不到 `R.string`。
 * 两个目录都由 build.gradle.kts 以系统属性传进来。
 */
class MentionAliasesTest {

    private val fixture: File by lazy {
        val dir = System.getProperty("imCardFixtures")
            ?: error("imCardFixtures system property missing — see feature-im/build.gradle.kts.")
        File(dir, "mention_everyone_aliases.json")
    }

    private val resDir: File by lazy {
        val dir = System.getProperty("featureImResDir")
            ?: error("featureImResDir system property missing — see feature-im/build.gradle.kts.")
        File(dir)
    }

    /** 从某个 res 目录的 strings.xml 里取 im_mention_everyone。 */
    private fun everyoneLabel(valuesDir: String): String {
        val xml = File(resDir, "$valuesDir/strings.xml").readText()
        val re = Regex("""<string name="im_mention_everyone">([^<]*)</string>""")
        return re.find(xml)?.groupValues?.get(1)
            ?: error("im_mention_everyone missing from $valuesDir/strings.xml")
    }

    /**
     * 路径悄悄断掉会让下面每一条断言都变成空转,所以可达性本身就是一条测试 ——
     * 刻意让它失败而不是 Assume 跳过:一个静默跳过的契约测试比没有更糟,因为它
     * 还在报绿。
     */
    @Test
    fun `fixture and res dir are both reachable`() {
        assertTrue(
            "Alias fixture not found at ${fixture.absolutePath}. It lives in the " +
                "we-meet backend repo (core/tests/fixtures/im_cards).",
            fixture.isFile,
        )
        assertTrue(resDir.isDirectory)
    }

    @Test
    fun `constant matches the backend fixture`() {
        val json = JSONObject(fixture.readText())
        val aliases = json.getJSONArray("aliases").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        assertEquals(aliases.sorted(), MENTION_EVERYONE_ALIASES.sorted())

        // fixture 内部自洽:aliases 就是 by_locale 的扁平投影。
        val byLocale = json.getJSONObject("by_locale")
        val values = byLocale.keys().asSequence().map { byLocale.getString(it) }.toList()
        assertEquals(aliases.sorted(), values.sorted())
    }

    @Test
    fun `every shipped locale's label is in the alias table, and nothing extra is`() {
        val labels = listOf(
            "values" to "Everyone",
            "values-zh-rCN" to null,
            "values-de" to null,
            "values-fr" to null,
            "values-nl" to null,
        ).map { (dir, _) -> everyoneLabel(dir) }

        for (label in labels) {
            assertTrue(
                "'$label' is a shipped locale label but not in MENTION_EVERYONE_ALIASES " +
                    "— users of that locale would never light up on @everyone.",
                label in MENTION_EVERYONE_ALIASES,
            )
        }
        // 反向:别名表里不该有任何 locale 都不用的僵尸项。
        assertEquals(labels.distinct().sorted(), MENTION_EVERYONE_ALIASES.sorted())
    }

    // --- 行为 ------------------------------------------------------------------

    @Test
    fun `mentionsEveryone recognises every locale, case-insensitively`() {
        for (alias in MENTION_EVERYONE_ALIASES) {
            assertTrue(alias, mentionsEveryone("hi @$alias please read"))
        }
        assertTrue(mentionsEveryone("@everyone ping"))
        assertTrue(mentionsEveryone("@ALLE Achtung"))
    }

    @Test
    fun `no at prefix is not a mention`() {
        assertFalse(mentionsEveryone("Everyone is here"))
    }

    private val me = listOf<String?>("Wang Xiaoming")

    @Test
    fun `text scans literals`() {
        assertEquals(
            MentionHit(self = true, everyone = false),
            mentionScan("text", "@Wang Xiaoming have a look", me),
        )
        assertEquals(
            MentionHit(self = false, everyone = true),
            mentionScan("text", "@Alle bitte lesen", me),
        )
    }

    @Test
    fun `rich-text resolves at-everyone structurally, not by literal`() {
        // plain 里刻意不放任何别名 —— 全靠 at 标签认出来。
        val body = """
            {"v":1,"title":"build failed",
             "content":[[{"tag":"at","uid":"all","name":"all"}]],
             "plain":"build failed please fix"}
        """.trimIndent()
        assertTrue(mentionScan("rich-text", body, me).everyone)
    }

    @Test
    fun `rich-text still honours a literal typed by the bot`() {
        val body = """
            {"v":1,"title":"","content":[[{"tag":"text","text":"@Iedereen let op"}]],
             "plain":"@Iedereen let op"}
        """.trimIndent()
        assertTrue(mentionScan("rich-text", body, me).everyone)
    }

    @Test
    fun `rich-text never matches self by at-uid`() {
        // at.uid 是 webhook 发送方随手填的外部字符串,不是我们的 im uid ——
        // 拿它跟自己比等于让外部「猜中 uid 就能定向戳人」。
        val body = """
            {"v":1,"title":"","content":[[{"tag":"at","uid":"Wang Xiaoming","name":"someone"}]],
             "plain":"someone"}
        """.trimIndent()
        assertFalse(mentionScan("rich-text", body, me).self)
    }

    @Test
    fun `quote scans the reply body but not the quoted snippet`() {
        // 引用一条 @所有人 会让所有人**再被通知一次** —— 那是 bug 不是设计。
        val quoted = """{"reply_to":{"sender":"Zhang","snippet":"@Alle meeting"},"text":"ok"}"""
        assertFalse(mentionScan("quote", quoted, me).everyone)

        val atMe = """{"reply_to":{"sender":"Zhang","snippet":"x"},"text":"@Wang Xiaoming look"}"""
        assertTrue(mentionScan("quote", atMe, me).self)
    }

    @Test
    fun `other content types are never scanned`() {
        val card = """{"title":"@Alle weekly","status":"ongoing"}"""
        for (ct in listOf("meeting-card", "doc-card", "event-card", "image", "file", "merged", "system")) {
            assertEquals(ct, MentionHit(), mentionScan(ct, card, me))
        }
    }

    @Test
    fun `malformed json is not a mention and does not throw`() {
        assertEquals(MentionHit(), mentionScan("rich-text", "{ not json", me))
        assertEquals(MentionHit(), mentionScan("quote", "nope", me))
    }
}
