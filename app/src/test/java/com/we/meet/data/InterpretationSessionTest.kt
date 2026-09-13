package com.we.meet.data

import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import org.junit.Assert.*
import org.junit.Test

class InterpretationSessionTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private val channel = InterpretationChannelDto(id(1), "en", 1, "translating", "", null)
    private val sub = InterpretationSubscriptionDto(id(2), channel.id, id(3), 1, true, 20.0, "2026-09-13T00:00:20Z")
    private val state = InterpretationStateDto(true, false, false, listOf("zh", "en"), listOf(channel), listOf(TranslationConnectionDto(id(3), "PA_me")), listOf(sub), 20)
    private val selection = InterpretationSession.selection(sub)
    private val session = InterpretationSession("RM_me", "PA_me")
    private val sender = "interpretation-${channel.id}-worker"
    private val active = setOf("PA_me", "PA_speaker")
    private fun packet(type: String = "ready", source: String = id(4), sourceSid: String = "PA_speaker", track: String = "TR_audio", generation: Int = 1, revision: Int = 1, target: String = "en", text: String = "hello") =
        """{"type":"$type","channel_id":"${channel.id}","generation":$generation,"target":"$target","subscription_id":"${sub.id}","subscription_revision":$revision,"source_participation_id":"$source","source_participant_sid":"$sourceSid","audio_track_sid":"$track","response_id":"response","item_id":"item","text":"$text"}""".toByteArray()
    private fun receive(bytes: ByteArray = packet(), identity: String = sender, agentSid: String = "PA_agent", speakers: Set<String> = active) = session.receive(bytes, identity, agentSid, true, speakers, 100)
    private fun prepare() { session.accept(state, 0, 100); assertTrue(session.listen(selection, 100)) }
    @Test fun readingAnExistingSubscriptionNeverStartsListening() {
        session.accept(state, 0, 100)
        assertNull(session.desired); assertFalse(receive()); assertTrue(session.grants(active, 100).isEmpty())
        assertTrue(session.listen(selection, 100)); assertTrue(session.grants(active, 100).isEmpty())
        assertTrue(receive()); assertEquals("TR_audio", session.grants(active, 100).single().trackSid)
    }
    @Test fun exactAgentSourceAndTrackAreRequiredForEachGrant() {
        prepare(); assertTrue(receive())
        assertFalse(receive(agentSid = "PA_reconnected_agent")); assertFalse(receive(identity = "$sender-other"))
        assertFalse(receive(packet(source = id(5), track = "TR_audio")))
        assertFalse(receive(packet(sourceSid = "PA_absent")))
        assertFalse(receive(packet(sourceSid = "PA_me")))
        assertTrue(session.grants(setOf("PA_me"), 100).isEmpty())
    }
    @Test fun channelGenerationTargetAndSubscriptionRevisionCannotBeReused() {
        prepare()
        assertFalse(receive(packet(generation = 2))); assertFalse(receive(packet(revision = 2))); assertFalse(receive(packet(target = "zh")))
        assertNull(InterpretationEvents.decode(packet(), sender, false, channel, sub))
        assertNull(InterpretationEvents.decode(packet(), sender, true, channel, sub.copy(id = id(8))))
        assertNull(InterpretationEvents.decode(packet(), sender, true, channel.copy(state = "prepared"), sub))
    }
    @Test fun playbackAndRenewalExpireFromDispatchTimeAndDoNotAutoResume() {
        prepare(); receive(); assertEquals(15000L, session.grants(active, 100).single().expiresAtElapsedMs)
        assertTrue(session.renewed(selection, sub, 10000, 10001))
        assertTrue(session.grants(active, 15000).isEmpty())
        session.expire(15000); assertNull(session.desired)
        session.accept(state, 15000, 15001); assertTrue(session.grants(active, 15001).isEmpty())
        assertFalse(session.renewed(selection, sub, 15000, 15001))
    }
    @Test fun changedSubscriptionOrPermissionRevokesListening() {
        prepare(); receive()
        session.accept(state.copy(subscriptions = listOf(sub.copy(revision = 2))), 100, 100)
        assertNull(session.desired); assertFalse(receive())
        session.accept(state, 100, 100); session.listen(selection, 100); receive()
        session.accept(state.copy(available = false), 100, 100); assertNull(session.desired)
        session.accept(state, 100, 100); assertTrue(session.grants(active, 100).isEmpty())
    }
    @Test fun aLateRenewalForPreviousChoiceCannotReplaceTheNewChoice() {
        prepare(); receive()
        val nextChannel = channel.copy(id = id(6), target = "zh")
        val nextSub = sub.copy(channelId = nextChannel.id, revision = 2)
        val next = InterpretationSession.selection(nextSub)
        session.accept(state.copy(channels = listOf(channel, nextChannel), subscriptions = listOf(nextSub)), 100, 100)
        assertTrue(session.listen(next, 100))
        assertFalse(session.renewed(selection, sub, 100, 100)); assertEquals(next, session.desired)
        assertFalse(session.renewed(next, nextSub.copy(participationId = id(8)), 100, 100)); assertNull(session.desired)
    }
    @Test fun confirmedTextRequiresAReadySourceAndCannotBeOverwritten() {
        prepare(); assertFalse(receive(packet(type = "target_candidate")))
        receive(); assertTrue(receive(packet(type = "target_candidate"))); assertEquals("hello", session.rows.single().text)
        assertTrue(receive(packet(type = "target_final", text = "confirmed")))
        receive(packet(type = "target_candidate", text = "late")); assertEquals("confirmed", session.rows.single().text)
        assertFalse(receive(packet(type = "target_final", track = "TR_unknown")))
        session.clear(); assertTrue(session.rows.isEmpty()); assertNull(session.status)
    }
    @Test fun sourceCountIsBoundedAndDepartedSourcesCanBeReplaced() {
        prepare()
        val speakers = (1..17).map { "PA_speaker$it" }.toSet()
        for (i in 1..16) assertTrue(receive(packet(source = id(10 + i), sourceSid = "PA_speaker$i", track = "TR_audio$i"), speakers = speakers))
        assertFalse(receive(packet(source = id(27), sourceSid = "PA_speaker17", track = "TR_audio17"), speakers = speakers))
        assertEquals(16, session.grants(speakers, 100).size)
        assertTrue(receive(packet(source = id(27), sourceSid = "PA_speaker17", track = "TR_audio17"), speakers = speakers - "PA_speaker1"))
        assertEquals(16, session.grants(speakers - "PA_speaker1", 100).size)
    }
    @Test fun soundMutePreservesTextSubscriptionButLeavingClearsEverything() {
        prepare(); receive(); receive(packet(type = "target_final"))
        session.sound(false, 100); assertEquals(selection, session.desired); assertEquals(1, session.rows.size); assertTrue(session.grants(active, 100).isEmpty())
        session.sound(true, 100); assertEquals(1, session.grants(active, 100).size)
        session.silence(); assertNull(session.desired); assertTrue(session.rows.isEmpty())
        session.sound(true, 100); assertTrue(session.grants(active, 100).isEmpty())
    }
    @Test fun malformedUtf8OversizedPayloadAndInvalidIdsAreRejected() {
        for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), packet() + ByteArray(14000), packet(source = "not-an-id"), packet(track = "url"), packet(type = "execute"), packet(type = "target_final", text = "a".repeat(20001)))) {
            assertNull(InterpretationEvents.decode(bad, sender, true, channel, sub))
        }
        assertFalse(InterpretationEvents.decode(packet(type = "target_final"), sender, true, channel, sub)!!.toString().contains("hello"))
    }
    @Test fun historicalRowsRemainBoundedAndDoNotCollapseDifferentSpeakers() {
        val event = InterpretationEvents.decode(packet(type = "target_final"), sender, true, channel, sub)!!
        var rows = InterpretationEvents.update(emptyList(), event)
        rows = InterpretationEvents.update(rows, event.copy(sourceParticipationId = id(9), sourceParticipantSid = "PA_other"))
        assertEquals(2, rows.size)
        repeat(100) { rows = InterpretationEvents.update(rows, event.copy(itemId = "item-$it")) }
        assertEquals(60, rows.size); assertTrue(rows.first().id.endsWith("item-40"))
        assertFalse(rows.last().toString().contains("hello"))
    }
}
