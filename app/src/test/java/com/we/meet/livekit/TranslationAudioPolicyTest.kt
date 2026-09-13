package com.we.meet.livekit

import org.junit.Assert.*
import org.junit.Test

class TranslationAudioPolicyTest {
    private val grant = TranslationAudioGrant("RM_current", "PA_me", "translation-run-worker", "PA_agent", "TR_audio", 15000)
    private fun allows(value: TranslationAudioGrant = grant, now: Long = 0, room: String? = "RM_current", local: String = "PA_me", identity: String = grant.agentIdentity, participant: String = "PA_agent", track: String = "TR_audio", agent: Boolean = true) = TranslationAudioPolicy.allows(value, now, room, local, identity, participant, track, agent)
    @Test fun onlyTheExactAuthorizedTrackPlays() {
        assertTrue(allows())
        assertFalse(allows(room = "RM_reused")); assertFalse(allows(local = "PA_reconnected"))
        assertFalse(allows(participant = "PA_rejoined_agent")); assertFalse(allows(identity = "translation-run-other_worker"))
        assertFalse(allows(track = "TR_new")); assertFalse(allows(agent = false)); assertFalse(allows(room = null))
    }
    @Test fun ExpiredOrUnboundedLeaseCannotPlay() {
        assertTrue(allows(now = 14999)); assertFalse(allows(now = 15000)); assertFalse(allows(now = 16000))
        assertFalse(allows(grant.copy(expiresAtElapsedMs = Long.MAX_VALUE)))
        assertFalse(allows(grant.copy(expiresAtElapsedMs = 20001)))
    }
    @Test fun OrdinaryTracksRemainAutomaticButUnknownAndReservedIdentitiesDoNot() {
        assertTrue(TranslationAudioPolicy.ordinary("person", true))
        assertTrue(TranslationAudioPolicy.ordinary("other-ai-assistant", true))
        assertFalse(TranslationAudioPolicy.ordinary("", true)); assertFalse(TranslationAudioPolicy.ordinary("person", false))
        assertFalse(TranslationAudioPolicy.ordinary("translation-anything", true))
        assertFalse(TranslationAudioPolicy.ordinary("interpretation-anything", true))
    }
    @Test fun SharedTrackGrantsRemainExactAndCannotGrantOrdinaryHumanIdentity() {
        val shared = grant.copy(agentIdentity = "interpretation-channel-worker")
        assertTrue(allows(shared, identity = shared.agentIdentity))
        assertFalse(allows(grant.copy(agentIdentity = "human"), identity = "human"))
        assertFalse(allows(grant.copy(trackSid = "audio"), track = "audio"))
        assertFalse(allows(grant.copy(agentParticipantSid = "identity"), participant = "identity"))
    }
}
