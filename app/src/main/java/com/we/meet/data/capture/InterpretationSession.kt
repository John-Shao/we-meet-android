package com.we.meet.data.capture

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingInterpretationRepository
import com.we.meet.livekit.TranslationAudioGrant

data class InterpretationSelection(val id: String, val revision: Long, val channelId: String)
private data class InterpretationSource(val participantSid: String, val trackSid: String)

/** Only explicit foreground selection creates a desire to listen. Historical receipts are not inputs. */
class InterpretationSession(private val roomSid: String, private val localSid: String) {
    var status: InterpretationStateDto? = null; private set
    var desired: InterpretationSelection? = null; private set
    var muted = true; private set
    var rows: List<InterpretationRow> = emptyList(); private set
    private var statusStartedAt = 0L
    private var statusUntil = 0L
    private var leaseUntil = 0L
    private var sender: String? = null
    private var senderSid: String? = null
    private val sources = linkedMapOf<String, InterpretationSource>()
    val ready get() = sources.isNotEmpty()
    val connection get() = status?.connections?.singleOrNull { it.participantSid == localSid }
    val subscription get() = status?.subscriptions?.singleOrNull { it.participationId == connection?.id }
    val channel get() = status?.channels?.singleOrNull { it.id == desired?.channelId }
    fun fresh(now: Long) = now >= 0 && now < statusUntil && statusUntil - now <= 15000
    fun authorized(now: Long) = desired != null && fresh(now) && now < leaseUntil && leaseUntil - now <= 20000 && status?.available == true && channel?.state in setOf("starting", "translating")
    fun accept(value: InterpretationStateDto, requestedAt: Long, now: Long) {
        val previousChannel = channel
        status = value; statusStartedAt = requestedAt
        statusUntil = if (requestedAt in 0..now && requestedAt <= Long.MAX_VALUE - 15000) requestedAt + 15000 else 0
        val wanted = desired ?: return
        val observed = subscription
        if (!fresh(now) || !value.available || observed == null || selection(observed) != wanted || !observed.active || channel?.state !in setOf("starting", "translating") || previousChannel?.generation != channel?.generation) {
            silence(); return
        }
        leaseUntil = MeetingInterpretationRepository.deadline(observed, requestedAt, now)
        if (leaseUntil == 0L) silence()
    }
    fun listen(expected: InterpretationSelection, now: Long): Boolean {
        val observed = subscription ?: return false
        val target = status?.channels?.singleOrNull { it.id == expected.channelId } ?: return false
        if (!fresh(now) || status?.available != true || selection(observed) != expected || target.state !in setOf("prepared", "starting", "translating")) return false
        val deadline = MeetingInterpretationRepository.deadline(observed, statusStartedAt, now)
        if (deadline == 0L) return false
        silence(); desired = expected; leaseUntil = deadline; muted = false
        return true
    }
    /** Late renewals for a previous selection cannot affect a newer selection. */
    fun renewed(expected: InterpretationSelection, value: InterpretationSubscriptionDto, requestedAt: Long, now: Long): Boolean {
        if (desired != expected) return false
        if (!fresh(now) || status?.available != true || selection(value) != expected || value.participationId != connection?.id || runCatching { MeetingInterpretationRepository.subscription(value) }.isFailure) {
            silence(); return false
        }
        val deadline = MeetingInterpretationRepository.deadline(value, requestedAt, now)
        if (deadline == 0L) { silence(); return false }
        leaseUntil = deadline; return true
    }
    fun sound(enabled: Boolean, now: Long) { if (!enabled || authorized(now)) muted = !enabled }
    fun silence() { desired = null; muted = true; leaseUntil = 0; sender = null; senderSid = null; sources.clear(); rows = emptyList() }
    fun clear() { silence(); status = null; statusUntil = 0 }
    fun expire(now: Long) { if (desired != null && (!fresh(now) || now >= leaseUntil)) silence() }
    fun pruneSources(activeSids: Set<String>) { sources.entries.removeAll { it.value.participantSid !in activeSids } }
    fun receive(payload: ByteArray, identity: String, participantSid: String, agent: Boolean, activeSids: Set<String>, now: Long): Boolean {
        if (!authorized(now) || !participantSid.matches(Regex("PA_[A-Za-z0-9_-]{1,61}"))) return false
        val observed = subscription ?: return false
        val event = InterpretationEvents.decode(payload, identity, agent, channel ?: return false, observed) ?: return false
        if (sender != null && (sender != identity || senderSid != participantSid) || event.sourceParticipantSid !in activeSids) return false
        pruneSources(activeSids)
        if (event.type == "ready") {
            val old = sources[event.sourceParticipationId]
            if (old != null && old.participantSid != event.sourceParticipantSid || old == null && sources.size >= 16 || sources.any { it.key != event.sourceParticipationId && it.value.trackSid == event.audioTrackSid }) return false
            sender = identity; senderSid = participantSid
            sources[event.sourceParticipationId] = InterpretationSource(event.sourceParticipantSid, event.audioTrackSid)
        } else {
            if (sources[event.sourceParticipationId] != InterpretationSource(event.sourceParticipantSid, event.audioTrackSid)) return false
            rows = InterpretationEvents.update(rows, event)
        }
        return true
    }
    fun grants(activeSids: Set<String>, now: Long): List<TranslationAudioGrant> {
        pruneSources(activeSids)
        if (muted || !authorized(now) || channel?.state != "translating") return emptyList()
        return sources.values.map { TranslationAudioGrant(roomSid, localSid, sender ?: return emptyList(), senderSid ?: return emptyList(), it.trackSid, minOf(statusUntil, leaseUntil)) }
    }
    companion object {
        fun selection(value: InterpretationSubscriptionDto) = InterpretationSelection(value.id, value.revision, value.channelId)
    }
}
