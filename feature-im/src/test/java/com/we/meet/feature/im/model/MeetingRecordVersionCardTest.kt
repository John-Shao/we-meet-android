package com.we.meet.feature.im.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeetingRecordVersionCardTest {
    private val record = "11111111-1111-4111-8111-111111111111"
    private val version = "22222222-2222-4222-8222-222222222222"
    private fun body() = JSONObject().put("v", 1).put("record_id", record).put("title", "Old minutes").put("scope", "minutes")
    private fun parse(body: JSONObject) = MessageContentParser.parse("meeting-record-card", body.toString())

    @Test fun oldUnpinnedCardsRemainReadable() {
        val card = parse(body()) as MessageContent.MeetingRecordCard
        assertEquals("/meeting/records/$record?tab=summary", card.relativeLink())
        assertEquals("", card.previewSelector())
        assertEquals("record", (parse(body().put("scope", "record")) as MessageContent.MeetingRecordCard).scope)
    }

    @Test fun forwardedVersionsKeepTheirPreviewAndOpenTargets() {
        for (kind in listOf("summary", "human")) {
            val raw = body().put("${kind}_id", version)
            val card = parse(raw) as MessageContent.MeetingRecordCard
            assertEquals("/meeting/records/$record?$kind=$version", card.relativeLink())
            assertEquals("?${kind}_id=$version", card.previewSelector())
            assertEquals(card, parse(JSONObject(raw.toString())))
        }
    }

    @Test fun malformedAmbiguousOrWrongScopePinsNeverOpenLatest() {
        val invalid = listOf(body().put("summary_id", ""), body().put("human_id", JSONObject.NULL),
            body().put("human_id", "bad"), body().put("summary_id", version).put("human_id", version),
            body().put("scope", "record").put("summary_id", version))
        invalid.forEach { assertTrue(it.toString(), parse(it) is MessageContent.Unsupported) }
    }
}
