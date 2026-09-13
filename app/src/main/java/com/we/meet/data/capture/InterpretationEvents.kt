package com.we.meet.data.capture

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingTranslationRepository
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class InterpretationEvent(
    val type: String, @Json(name = "channel_id") val channelId: String, val generation: Long, val target: String,
    @Json(name = "subscription_id") val subscriptionId: String, @Json(name = "subscription_revision") val subscriptionRevision: Long,
    @Json(name = "source_participation_id") val sourceParticipationId: String,
    @Json(name = "source_participant_sid") val sourceParticipantSid: String,
    @Json(name = "audio_track_sid") val audioTrackSid: String,
    @Json(name = "response_id") val responseId: String? = null, @Json(name = "item_id") val itemId: String? = null,
    val text: String? = null, val stash: String? = null,
) { override fun toString() = "InterpretationEvent(<private>)" }
data class InterpretationRow(val id: String, val sourceSid: String, val text: String, val stash: String, val final: Boolean) {
    override fun toString() = "InterpretationRow(<private>)"
}
object InterpretationEvents {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(InterpretationEvent::class.java)
    fun decode(payload: ByteArray, identity: String, agent: Boolean, channel: InterpretationChannelDto, subscription: InterpretationSubscriptionDto): InterpretationEvent? {
        if (!agent || identity.length > 256 || !identity.startsWith("interpretation-${channel.id}-") || payload.size !in 2..14000 || channel.state !in setOf("translating", "stopping") || !subscription.active || subscription.channelId != channel.id) return null
        return runCatching {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            requireNotNull(adapter.fromJson(text)).also {
                require(it.channelId == channel.id && it.generation == channel.generation && it.target == channel.target && it.subscriptionId == subscription.id && it.subscriptionRevision == subscription.revision)
                MeetingTranslationRepository.uuid(it.sourceParticipationId); MeetingTranslationRepository.participant(it.sourceParticipantSid)
                require(it.audioTrackSid.matches(Regex("TR_[A-Za-z0-9_-]{1,61}")))
                if (it.type != "ready") {
                    require(it.type in setOf("target_candidate", "target_final") && it.responseId?.length in 1..128 && it.itemId?.length in 1..128)
                    require(it.text != null && it.text.length <= 20000 && (it.stash?.length ?: 0) <= 20000)
                }
            }
        }.getOrNull()
    }
    fun update(rows: List<InterpretationRow>, event: InterpretationEvent): List<InterpretationRow> {
        if (event.type == "ready") return rows
        val id = "${event.sourceParticipationId}:${event.responseId}:${event.itemId}"
        val old = rows.find { it.id == id }
        if (old?.final == true) return rows
        val row = InterpretationRow(id, event.sourceParticipantSid, requireNotNull(event.text), event.stash.orEmpty(), event.type == "target_final")
        return (if (old == null) rows + row else rows.map { if (it.id == id) row else it }).takeLast(60)
    }
}
