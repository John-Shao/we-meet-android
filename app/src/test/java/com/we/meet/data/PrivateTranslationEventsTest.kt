package com.we.meet.data

import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import org.junit.Assert.*
import org.junit.Test

class PrivateTranslationEventsTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val run = PrivateTranslationRunDto(id, 2, "translating", PrivateTranslationConfigurationDto("zh", "en", "push_to_talk", true, "qwen3.5-livetranslate-flash-realtime", "controller_only"), "PA_source", "")
    private val sender = "translation-$id-worker"
    private val ready = """{"run_id":"$id","generation":2,"type":"ready","sequence":4,"direction":null,"awaiting":false,"audio_track_sid":"TR_audio"}"""
    private val candidate = """{"run_id":"$id","generation":2,"type":"target_candidate","direction":"forward","response_id":"response","item_id":"item","text":"hello","stash":"world"}"""
    private fun decode(value: String, identity: String = sender, agent: Boolean = true, source: PrivateTranslationRunDto = run) = PrivateTranslationEvents.decode(value.toByteArray(), identity, agent, source)
    @Test fun readyAndBothTranslationDirectionsRequireTheExactAgentRun() {
        assertEquals("TR_audio", decode(ready)!!.audioTrackSid)
        assertEquals("forward", decode(candidate)!!.direction)
        assertEquals("reverse", decode(candidate.replace("forward", "reverse"))!!.direction)
        assertNull(decode(ready, agent = false)); assertNull(decode(ready, identity = "human-$id"))
        assertNull(decode(ready, source = run.copy(generation = 3)))
        assertNull(decode(ready, source = run.copy(state = "stopped")))
    }
    @Test fun invalidUtf8OversizedAndUnknownPayloadsAreRejected() {
        assertNull(PrivateTranslationEvents.decode(byteArrayOf(0xc3.toByte(), 0x28), sender, true, run))
        assertNull(decode(ready + " ".repeat(14000))); assertNull(decode("{}"))
        assertNull(decode(ready.replace("ready", "execute")))
        assertNull(decode(ready.replace("\"generation\":2", "\"generation\":2.5")))
    }
    @Test fun readyCannotGrantMalformedTrackOrSequence() {
        for (bad in listOf(ready.replace("TR_audio", "url"), ready.replace("\"sequence\":4", "\"sequence\":-1"), ready.replace("\"sequence\":4", "\"sequence\":9007199254740992"), ready.replace("\"direction\":null", "\"direction\":\"auto\""), ready.replace("\"awaiting\":false", "\"awaiting\":null"))) assertNull(decode(bad))
    }
    @Test fun textEventsAreBoundedAndRequireStableIds() {
        assertNull(decode(candidate.replace("\"item\"", "\"\"")))
        assertNull(decode(candidate.replace("hello", "a".repeat(20001))))
        assertNull(decode(candidate.replace("forward", "both")))
        assertNull(decode(candidate.replace("\"text\":\"hello\"", "\"text\":null")))
        assertFalse(decode(candidate)!!.toString().contains("hello"))
    }
    @Test fun confirmedRowsCannotBeOverwrittenByLateCandidatesAndHistoryIsBounded() {
        val first = decode(candidate)!!
        var rows = PrivateTranslationEvents.update(emptyList(), first)
        rows = PrivateTranslationEvents.update(rows, first.copy(type = "target_final", text = "confirmed"))
        assertEquals(rows, PrivateTranslationEvents.update(rows, first.copy(text = "late")))
        repeat(100) { rows = PrivateTranslationEvents.update(rows, first.copy(itemId = "item-$it")) }
        assertEquals(60, rows.size); assertTrue(rows.first().id.endsWith("item-40"))
        assertFalse(rows.last().toString().contains("hello"))
        assertEquals(rows, PrivateTranslationEvents.update(rows, decode(ready)!!))
    }
}
