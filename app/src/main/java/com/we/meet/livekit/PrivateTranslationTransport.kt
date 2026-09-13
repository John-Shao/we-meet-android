package com.we.meet.livekit

import com.we.meet.data.capture.PrivateTranslationCommand
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class TranslationPacket(val payload: ByteArray, val identity: String, val participantSid: String, val agent: Boolean) {
    override fun toString() = "TranslationPacket(<private>)"
}
interface PrivateTranslationTransport {
    val localSid: String
    fun current(): Boolean
    fun agents(): List<String>
    fun packets(): Flow<TranslationPacket>
    suspend fun send(command: PrivateTranslationCommand)
    fun grants(values: List<TranslationAudioGrant>)
}
class LiveKitPrivateTranslationTransport(private val room: Room, private val subscriptions: MeetingTrackSubscriptions,
    private val roomSid: String, override val localSid: String) : PrivateTranslationTransport {
    override fun current() = room.state == Room.State.CONNECTED && room.sid?.sid == roomSid && room.localParticipant.sid.value == localSid
    override fun agents() = room.remoteParticipants.values.filter { it.kind == Participant.Kind.AGENT }.mapNotNull { it.identity?.value }.filter { it.startsWith("translation-") }.take(16)
    override fun packets(): Flow<TranslationPacket> = flow {
        room.events.collect { event ->
            if (event is RoomEvent.DataReceived && event.topic == "meeting.translation.events" && current()) {
                val participant = event.participant
                if (participant != null) emit(TranslationPacket(event.data, participant.identity?.value.orEmpty(), participant.sid.value, participant.kind == Participant.Kind.AGENT))
            }
        }
    }
    override suspend fun send(command: PrivateTranslationCommand) {
        check(current())
        room.localParticipant.publishData(command.payload, topic = "meeting.translation.control", identities = listOf(Participant.Identity(command.destination))).getOrThrow()
    }
    override fun grants(values: List<TranslationAudioGrant>) { subscriptions.update("private", if (current()) values else emptyList()) }
}
