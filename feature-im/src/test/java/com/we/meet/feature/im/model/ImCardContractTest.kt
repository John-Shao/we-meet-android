package com.we.meet.feature.im.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test against the backend's golden card fixtures.
 *
 * These are the *same files* `core/tests/test_im_card_contract.py` asserts and
 * the web suite parses (`imCardContract.test.ts`). Reading them here — rather
 * than keeping an Android-local copy — is the whole point: a copy would drift,
 * and drift is exactly what this is meant to catch. A protocol change now has
 * to update one committed fixture, which makes the other two suites go red.
 *
 * The fixture directory lives in the sibling `we-meet` checkout; the path is
 * handed over as the `imCardFixtures` system property (see build.gradle.kts,
 * overridable with `-PimCardFixtures=<dir>`).
 */
class ImCardContractTest {

    private val fixtureDir: File by lazy {
        val configured = System.getProperty("imCardFixtures")
            ?: error(
                "imCardFixtures system property missing — the unit-test task is " +
                    "supposed to set it (feature-im/build.gradle.kts).",
            )
        File(configured)
    }

    private fun load(name: String): String = File(fixtureDir, "$name.json").readText()

    private fun parse(contentType: String, name: String): MessageContent =
        MessageContentParser.parse(contentType, load(name))

    /**
     * A silent path break would turn every assertion below into a no-op, so the
     * reachability of the fixtures is itself a test. Deliberately a failure and
     * not an `Assume` skip — a contract test that quietly opts out is worse than
     * no contract test, because it still reports green.
     */
    @Test
    fun `fixture directory is reachable from the android repo`() {
        assertTrue(
            "Golden fixtures not found at ${fixtureDir.absolutePath}. They live in " +
                "the we-meet backend repo (core/tests/fixtures/im_cards). Check out " +
                "both repos side by side, or pass -PimCardFixtures=<dir>.",
            fixtureDir.isDirectory,
        )
        assertTrue(fixtureDir.listFiles()?.isNotEmpty() == true)
    }

    // --- event-card ----------------------------------------------------------

    @Test
    fun `parses a created card`() {
        val card = parse("event-card", "event_card_created")
        assertTrue(card is MessageContent.EventCard)
        card as MessageContent.EventCard
        assertEquals("created", card.kind)
        assertEquals("季度评审", card.title)
        assertEquals(4, card.attendeeCount)
        assertEquals("张三", card.organizerName)
        assertEquals("11111111-1111-4111-8111-111111111111", card.eventId)
    }

    @Test
    fun `parses canonical dates from an all-day card`() {
        val card = parse("event-card", "event_card_all_day") as MessageContent.EventCard
        assertTrue(card.allDay)
        assertEquals("2026-08-12", card.startDate)
        assertEquals("2026-08-14", card.endDate)
        assertEquals("2026-08-10", card.oldStartDate)
        assertEquals("2026-08-12", card.oldEndDate)
    }

    @Test
    fun `parses a redacted private card`() {
        val card = parse("event-card", "event_card_private") as MessageContent.EventCard
        assertEquals("private", card.visibility)
        assertEquals("", card.title)
        assertEquals(0, card.attendeeCount)
        assertEquals("", card.organizerName)
    }

    @Test
    fun `parses invitation and removal cards`() {
        val invited = parse("event-card", "event_card_invited") as MessageContent.EventCard
        val removed = parse("event-card", "event_card_removed") as MessageContent.EventCard
        assertEquals("invited", invited.kind)
        assertEquals("removed", removed.kind)
    }

    @Test
    fun `parses an RSVP reply card`() {
        val card = parse("event-card", "event_card_rsvp_changed") as MessageContent.EventCard
        assertEquals("rsvp_changed", card.kind)
        assertEquals("李四", card.responderName)
        assertEquals("accepted", card.rsvpStatus)
    }

    @Test
    fun `parses a time_changed card with the previous window`() {
        val card = parse("event-card", "event_card_time_changed") as MessageContent.EventCard
        assertEquals("time_changed", card.kind)
        // 新窗
        assertEquals("2026-08-11T02:00:00+00:00", card.startIso)
        assertEquals("2026-08-11T03:00:00+00:00", card.endIso)
        // 旧窗 —— App 端原本连字段都没有,Web 一直在渲染
        assertEquals("2026-08-10T02:00:00+00:00", card.oldStartIso)
        assertEquals("2026-08-10T03:00:00+00:00", card.oldEndIso)
    }

    @Test
    fun `parses an attendees_changed card`() {
        val card = parse("event-card", "event_card_attendees_changed") as MessageContent.EventCard
        assertEquals("attendees_changed", card.kind)
        assertEquals(2, card.addedCount)
        assertEquals(6, card.attendeeCount)
    }

    @Test
    fun `parses a cancelled card`() {
        val card = parse("event-card", "event_card_cancelled") as MessageContent.EventCard
        assertEquals("cancelled", card.kind)
    }

    @Test
    fun `parses recurrence scopes from series change fixtures`() {
        val moved = parse(
            "event-card",
            "event_card_recurrence_time_changed",
        ) as MessageContent.EventCard
        val cancelled = parse(
            "event-card",
            "event_card_recurrence_cancelled",
        ) as MessageContent.EventCard

        assertEquals("following", moved.recurrenceScope)
        assertEquals("all", cancelled.recurrenceScope)
    }

    @Test
    fun `unknown recurrence scope is ignored`() {
        val card = MessageContentParser.parse(
            "event-card",
            """{"title":"Series","recurrence_scope":"future-client-value"}""",
        ) as MessageContent.EventCard
        assertEquals("", card.recurrenceScope)
    }

    // --- doc-card ------------------------------------------------------------

    @Test
    fun `parses the shared document snapshot`() {
        val card = parse("doc-card", "doc_card")
        assertTrue(card is MessageContent.DocCard)
        card as MessageContent.DocCard
        assertEquals("产品需求文档", card.title)
        assertEquals("22222222-2222-4222-8222-222222222222", card.docId)
        assertTrue(card.url.contains("/docs/"))
    }

    // --- meeting-card --------------------------------------------------------

    @Test
    fun `parses an ongoing meeting`() {
        val card = parse("meeting-card", "meeting_card_ongoing") as MessageContent.MeetingCard
        assertEquals("ongoing", card.status)
        assertEquals("team-standup", card.slug)
        assertEquals("每日站会", card.title)
        assertEquals("33333333-3333-4333-8333-333333333333", card.roomId)
    }

    @Test
    fun `parses a scheduled meeting with its start time`() {
        val card = parse("meeting-card", "meeting_card_scheduled") as MessageContent.MeetingCard
        assertEquals("scheduled", card.status)
        assertEquals("2026-08-10T02:00:00+00:00", card.scheduledAtIso)
    }

    // --- the fallback the fixtures exist to protect ---------------------------

    /**
     * Every golden fixture must parse into its real type — never into
     * [MessageContent.Unsupported]. That fallback is the client's safety net for
     * *future* content types; if a fixture lands in it, the card silently
     * degrades to raw JSON in the bubble and nobody notices until a user does.
     */
    @Test
    fun `no golden fixture falls through to Unsupported`() {
        val byType = mapOf(
            "event-card" to listOf(
                "event_card_created",
                "event_card_all_day",
                "event_card_private",
                "event_card_invited",
                "event_card_time_changed",
                "event_card_attendees_changed",
                "event_card_removed",
                "event_card_rsvp_changed",
                "event_card_cancelled",
                "event_card_recurrence_time_changed",
                "event_card_recurrence_cancelled",
            ),
            "doc-card" to listOf("doc_card"),
            "meeting-card" to listOf("meeting_card_ongoing", "meeting_card_scheduled"),
            "rich-text" to listOf("rich_text_simple", "rich_text_full"),
            "rich-card" to listOf(
                "rich_card_minimal",
                "rich_card_full",
                "rich_card_degraded",
            ),
            "card-state" to listOf("rich_card_state"),
        )
        // 同一个目录里还住着几份**不是卡片正文**的三端共享 fixture(各有自己的
        // 契约测试)。显式点名排除,不按前缀糊掉 —— 糊掉之后再有人加卡片忘登记
        // 就又没人拦了,而拦这个正是下面那条断言唯一的用处。
        val nonCardFixtures = setOf(
            // @所有人 的全语种别名表(见 MentionAliasesTest)。
            "mention_everyone_aliases",
        )

        // 兜住「有人往目录里加了 fixture 但忘了在这里登记」——漏登记的话
        // 上面每条断言都还是绿的,这个测试才会红。
        val registered = byType.values.flatten().sorted()
        val onDisk = fixtureDir.listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json") }
            .filterNot { it in nonCardFixtures }
            .sorted()
        assertEquals("fixture 目录与本测试的登记表不一致", onDisk, registered)

        byType.forEach { (contentType, names) ->
            names.forEach { name ->
                val parsed = parse(contentType, name)
                assertTrue(
                    "$name parsed as Unsupported — the wire format moved and this " +
                        "client did not follow",
                    parsed !is MessageContent.Unsupported,
                )
            }
        }
    }

    // ---- rich-text (群机器人富文本) ----

    @Test
    fun `rich text parses a single paragraph`() {
        val content = parse("rich-text", "rich_text_simple")
        assertTrue(content is MessageContent.RichText)
        val body = (content as MessageContent.RichText).body
        assertEquals("部署完成", body.title)
        assertEquals(1, body.paragraphs.size)
        assertEquals(
            RichTextTag.Text("生产环境已更新到 v1.2.0"),
            body.paragraphs[0][0],
        )
    }

    @Test
    fun `rich text parses every tag a bot can send`() {
        val body = (parse("rich-text", "rich_text_full") as MessageContent.RichText).body
        assertEquals("构建失败", body.title)
        assertEquals(
            RichTextTag.Link("查看日志", "https://ci.example.com/runs/1"),
            body.paragraphs[0][1],
        )
        assertEquals(RichTextTag.At("all", "所有人"), body.paragraphs[1][0])
        // Images degrade server-side; the client only ever sees the placeholder.
        assertEquals(RichTextTag.Text("[图片]"), body.paragraphs[2][0])
    }

    @Test
    fun `rich text carries the server derived plain projection`() {
        val body = (parse("rich-text", "rich_text_full") as MessageContent.RichText).body
        // Never rendered — it backs the conversation preview, full-text search
        // and the substring-based "@我" check.
        assertTrue("plain should mention @所有人", body.plain.contains("@所有人"))
        assertTrue(RichTextParser.flatten(body).contains("构建失败"))
    }
}
