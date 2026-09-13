package com.we.meet.data

import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.PrivateTranslationSession
import org.junit.Assert.*
import org.junit.Test

class PrivateTranslationSessionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val sender = "translation-$id-worker"
    private val config = PrivateTranslationConfigurationDto("zh", "en", "push_to_talk", true, "qwen3.5-livetranslate-flash-realtime", "controller_only")
    private val run = PrivateTranslationRunDto(id, 1, "translating", config, "PA_me", "")
    private val state = PrivateTranslationStateDto(true, false, listOf("zh", "en"), run, emptyList())
    private val session = PrivateTranslationSession("RM_me", "PA_me")
    private fun ready(sequence: Long = 0, direction: String = "null", awaiting: Boolean = false, sid: String = "PA_agent") = session.receive("""{"run_id":"$id","generation":1,"type":"ready","sequence":$sequence,"direction":$direction,"awaiting":$awaiting,"audio_track_sid":"TR_audio"}""".toByteArray(), sender, sid, true, 100)
    private fun prepare() { session.accept(state, 0, 100); assertTrue(ready()) }
    @Test fun playbackRequiresExplicitSoundConsentAndFreshExactConnection() {
        prepare(); assertNull(session.grant(100)); assertTrue(session.setSound(true, 100))
        val grant = session.grant(100)!!
        assertEquals("RM_me", grant.roomSid); assertEquals("PA_me", grant.localParticipantSid); assertEquals("PA_agent", grant.agentParticipantSid)
        session.silence(); assertNull(session.grant(100))
        session.accept(state.copy(current = run.copy(sourceParticipantSid = "PA_other")), 100, 100)
        assertFalse(session.setSound(true, 100)); assertNull(session.grant(100))
    }
    @Test fun lateApiResponseDoesNotExtendPlaybackLease() {
        prepare(); session.setSound(true, 100)
        session.accept(state, 1000, 15999); assertNotNull(session.grant(15999)); assertNull(session.grant(16000))
        session.accept(state, 1000, 16001); assertNull(session.grant(16001)); assertTrue(session.muted)
        session.accept(state, 2000, 100); assertFalse(session.fresh(100))
    }
    @Test fun runChangeBackgroundFailureAndKillSwitchNeverRestoreAudioAutomatically() {
        prepare(); session.setSound(true, 100)
        session.accept(state.copy(available = false), 100, 100); assertNull(session.grant(100))
        session.accept(state, 100, 100); assertNull(session.grant(100))
        session.setSound(true, 100); session.clear(); assertNull(session.status); assertFalse(session.ready); assertTrue(session.rows.isEmpty())
        session.accept(state, 100, 100); ready(); assertNull(session.grant(100))
        session.setSound(true, 100); session.failedRead(); assertNull(session.grant(100))
        session.accept(state.copy(current = run.copy(generation = 2)), 100, 100); assertFalse(ready())
    }
    @Test fun agentConnectionCannotBeReplacedByAnotherSenderWithTheSameIdentity() {
        prepare(); assertFalse(ready(sid = "PA_rejoined")); session.setSound(true, 100)
        assertEquals("PA_agent", session.grant(100)!!.agentParticipantSid)
    }
    @Test fun manualCommandsAreConsecutiveRequireMicrophoneAndWaitForAuthoritativeCompletion() {
        prepare()
        assertNull(session.turn("forward", true, false, 100))
        val begin = session.turn("forward", true, true, 100)!!
        assertTrue(String(begin.payload).contains("\"sequence\":1")); assertEquals(sender, begin.destination)
        assertNull(session.turn("reverse", true, true, 100)); assertNull(session.turn("reverse", false, true, 100))
        val end = session.turn("forward", false, true, 100)!!
        assertTrue(String(end.payload).contains("\"sequence\":2")); assertTrue(session.awaiting)
        assertFalse(ready(sequence = 1))
        val complete = """{"run_id":"$id","generation":1,"type":"turn_completed","direction":"forward"}""".toByteArray()
        assertTrue(session.receive(complete, sender, "PA_agent", true, 100)); assertTrue(session.awaiting)
        assertNull(session.turn("reverse", true, true, 100))
        assertTrue(ready(sequence = 2)); assertNotNull(session.turn("reverse", true, true, 100))
    }
    @Test fun endingHeldSpeechRemainsPossibleAfterLeaseExpiryButNewSpeechDoesNot() {
        prepare(); assertNotNull(session.turn("forward", true, true, 100))
        assertNotNull(session.turn("forward", false, false, 16000))
        assertNull(session.turn("reverse", true, true, 16000))
    }
    @Test fun unknownDataCommandRequiresStopInsteadOfReplayingSpeech() {
        prepare(); session.setSound(true, 100); session.failedCommand()
        assertTrue(session.controlFailed); assertNull(session.grant(100)); assertFalse(ready())
        assertNull(session.turn("forward", true, true, 100)); assertNull(session.sync(sender, 100))
        session.accept(state, 100, 100); assertTrue(session.controlFailed)
        session.clear(); session.accept(state, 100, 100); assertTrue(session.controlFailed); assertFalse(ready())
        session.accept(state.copy(current = run.copy(generation = 2)), 100, 100); assertFalse(session.controlFailed)
    }
    @Test fun anotherConnectionOrTextOnlyRunCannotCreatePlaybackGrant() {
        session.accept(state.copy(current = run.copy(sourceParticipantSid = "PA_other")), 0, 100)
        assertFalse(ready()); assertNull(session.sync(sender, 100))
        session.accept(state.copy(current = run.copy(configuration = config.copy(audio = false))), 0, 100)
        assertTrue(ready()); assertFalse(session.setSound(true, 100)); assertNull(session.grant(100))
    }
}
