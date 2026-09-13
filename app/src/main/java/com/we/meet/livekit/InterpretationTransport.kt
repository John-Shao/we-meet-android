package com.we.meet.livekit

import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface InterpretationTransport {
    val localSid: String
    fun current(): Boolean
    fun activeSources(): Set<String>
    fun speakerName(sid: String): String?
    fun packets(): Flow<TranslationPacket>
    fun grants(values: List<TranslationAudioGrant>)
}
class LiveKitInterpretationTransport(private val room: Room, private val subscriptions: MeetingTrackSubscriptions,
    private val roomSid: String, override val localSid: String) : InterpretationTransport {
    override fun current() = room.state == Room.State.CONNECTED && room.sid?.sid == roomSid && room.localParticipant.sid.value == localSid
    override fun activeSources() = room.remoteParticipants.values.filter { it.kind == Participant.Kind.STANDARD }.map { it.sid.value }.toSet() + localSid
    override fun speakerName(sid: String): String? = if (sid == localSid) room.localParticipant.name else room.remoteParticipants.values.find { it.sid.value == sid }?.name
    override fun packets(): Flow<TranslationPacket> = flow {
        room.events.collect { event ->
            if (event is RoomEvent.DataReceived && event.topic == "meeting.interpretation.events" && current()) {
                val participant = event.participant
                if (participant != null) emit(TranslationPacket(event.data, participant.identity?.value.orEmpty(), participant.sid.value, participant.kind == Participant.Kind.AGENT))
            }
        }
    }
    override fun grants(values: List<TranslationAudioGrant>) { subscriptions.update("shared", if (current()) values else emptyList()) }
}
