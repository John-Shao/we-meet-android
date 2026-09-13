package com.we.meet.data.capture

import com.squareup.moshi.Moshi
import com.we.meet.data.api.dto.PrivateTranslationStateDto
import com.we.meet.livekit.TranslationAudioGrant

data class PrivateTranslationCommand(val destination: String, val payload: ByteArray) {
    override fun toString() = "PrivateTranslationCommand(<private>)"
}

/** One foreground connection. All time values use a monotonic clock; no audio or text is persisted. */
class PrivateTranslationSession(private val roomSid: String, private val localSid: String) {
    var status: PrivateTranslationStateDto? = null; private set
    var rows: List<PrivateTranslationRow> = emptyList(); private set
    var ready = false; private set
    var muted = true; private set
    var held: String? = null; private set
    var awaiting = false; private set
    var controlFailed = false; private set
    private var until = 0L
    private var sequence = 0L
    private var sender: String? = null
    private var senderSid: String? = null
    private var track: String? = null
    private var failedRun: Pair<String, Long>? = null
    val ownConnection get() = status?.current?.sourceParticipantSid == localSid

    fun fresh(now: Long) = now >= 0 && now < until && until - now <= 15000
    fun accept(value: PrivateTranslationStateDto, requestStartedAt: Long, now: Long) {
        val before = status?.current
        val current = value.current
        if (before?.id != current?.id || before?.generation != current?.generation || before?.sourceParticipantSid != current?.sourceParticipantSid) {
            reset(); rows = emptyList()
        }
        controlFailed = current != null && failedRun == (current.id to current.generation)
        status = value
        until = if (requestStartedAt in 0..now && requestStartedAt <= Long.MAX_VALUE - 15000) requestStartedAt + 15000 else 0
        if (!value.available || !ownConnection || current?.state !in setOf("starting", "translating") || !fresh(now)) silence()
    }
    fun silence() { muted = true }
    fun clear() { status = null; rows = emptyList(); until = 0; reset() }
    fun failedRead() { clear() }
    fun failedCommand() { status?.current?.let { failedRun = it.id to it.generation }; controlFailed = true; reset() }
    private fun reset() { silence(); ready = false; held = null; awaiting = false; sequence = 0; sender = null; senderSid = null; track = null }
    fun receive(payload: ByteArray, identity: String, participantSid: String, agent: Boolean, now: Long): Boolean {
        val current = status?.current ?: return false
        if (!ownConnection || !fresh(now) || !status!!.available || controlFailed || !participantSid.matches(Regex("PA_[A-Za-z0-9_-]{1,61}"))) return false
        val event = PrivateTranslationEvents.decode(payload, identity, agent, current) ?: return false
        if (sender != null && (sender != identity || senderSid != participantSid)) return false
        if (event.type == "ready") {
            if (event.sequence!! < sequence) return false
            sender = identity; senderSid = participantSid; sequence = event.sequence; track = event.audioTrackSid; ready = true
            held = if (current.configuration.mode == "push_to_talk") event.direction else null
            awaiting = current.configuration.mode == "push_to_talk" && event.awaiting == true
        } else if (ready) {
            // Only an authoritative ready response unlocks another manual turn. A duplicated
            // turn_completed event has no sequence and must not unlock a later response.
            rows = PrivateTranslationEvents.update(rows, event)
        } else return false
        return true
    }
    fun setSound(enabled: Boolean, now: Long): Boolean {
        if (!enabled) { silence(); return true }
        val current = status?.current ?: return false
        if (!fresh(now) || !status!!.available || !ownConnection || !ready || controlFailed || current.state != "translating" || !current.configuration.audio || track == null) return false
        muted = false; return true
    }
    fun grant(now: Long): TranslationAudioGrant? {
        val current = status?.current ?: return null
        if (muted || controlFailed || !ready || !fresh(now) || !status!!.available || !ownConnection || current.state != "translating" || !current.configuration.audio) return null
        return TranslationAudioGrant(roomSid, localSid, sender ?: return null, senderSid ?: return null, track ?: return null, until)
    }
    fun sync(destination: String, now: Long): PrivateTranslationCommand? {
        val current = status?.current ?: return null
        if (!fresh(now) || !status!!.available || !ownConnection || controlFailed || current.state !in setOf("starting", "translating") || !destination.startsWith("translation-${current.id}-")) return null
        return command(destination, mapOf("run_id" to current.id, "generation" to current.generation, "action" to "sync"))
    }
    fun turn(direction: String, begin: Boolean, microphoneEnabled: Boolean, now: Long): PrivateTranslationCommand? {
        val current = status?.current ?: return null
        val destination = sender ?: return null
        if (!ready || controlFailed || !ownConnection || current.configuration.mode != "push_to_talk" || direction !in setOf("forward", "reverse") || sequence >= 9007199254740991L) return null
        if (begin && (!fresh(now) || !status!!.available || current.state != "translating" || held != null || awaiting || !microphoneEnabled)) return null
        if (!begin && held != direction) return null
        held = if (begin) direction else null
        awaiting = !begin
        return command(destination, mapOf("run_id" to current.id, "generation" to current.generation, "sequence" to ++sequence, "action" to if (begin) "begin" else "end", "direction" to direction))
    }
    private fun command(destination: String, value: Map<String, Any>) = PrivateTranslationCommand(destination, Moshi.Builder().build().adapter(Any::class.java).toJson(value).toByteArray(Charsets.UTF_8))
}
