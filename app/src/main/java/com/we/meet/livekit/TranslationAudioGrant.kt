package com.we.meet.livekit

/** In-memory only; callers create grants from validated ready events and a fresh API response. */
data class TranslationAudioGrant(
    val roomSid: String, val localParticipantSid: String,
    val agentIdentity: String, val agentParticipantSid: String, val trackSid: String,
    val expiresAtElapsedMs: Long,
)

internal object TranslationAudioPolicy {
    fun protectedIdentity(identity: String) = identity.startsWith("translation-") || identity.startsWith("interpretation-")
    fun ordinary(identity: String, knownKind: Boolean) = identity.isNotBlank() && knownKind && !protectedIdentity(identity)
    fun allows(grant: TranslationAudioGrant, now: Long, roomSid: String?, localSid: String,
        identity: String, participantSid: String, trackSid: String, agent: Boolean): Boolean =
        agent && protectedIdentity(identity) && grant.roomSid == roomSid && grant.localParticipantSid == localSid &&
            grant.agentIdentity == identity && grant.agentParticipantSid == participantSid && grant.trackSid == trackSid &&
            now < grant.expiresAtElapsedMs && grant.expiresAtElapsedMs - now <= 20000 &&
            grant.roomSid.matches(Regex("RM_[A-Za-z0-9_-]{1,61}")) &&
            grant.localParticipantSid.matches(Regex("PA_[A-Za-z0-9_-]{1,61}")) &&
            grant.agentParticipantSid.matches(Regex("PA_[A-Za-z0-9_-]{1,61}")) &&
            grant.trackSid.matches(Regex("TR_[A-Za-z0-9_-]{1,124}"))
}
