package com.we.meet.livekit

import android.os.SystemClock
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import kotlinx.coroutines.*

/** Requires ConnectOptions.autoSubscribe=false. Ordinary meeting tracks still subscribe normally. */
class MeetingTrackSubscriptions(private val room: Room) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val grants = mutableMapOf<String, List<TranslationAudioGrant>>()
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Reconnecting, is RoomEvent.Disconnected, is RoomEvent.Reconnected -> clear()
                    is RoomEvent.Connected, is RoomEvent.ParticipantConnected, is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackPublished, is RoomEvent.TrackUnpublished, is RoomEvent.TrackSubscribed -> apply()
                    else -> Unit
                }
            }
        }
        scope.launch { while (isActive) { delay(250); apply() } }
    }
    /** Separate private/shared workspaces may revoke their own grants without affecting each other. */
    fun update(owner: String, values: List<TranslationAudioGrant>) {
        require(owner in setOf("private", "shared") && values.size <= 16)
        if (values.isEmpty()) grants.remove(owner) else grants[owner] = values.toList()
        apply()
    }
    fun clear() { grants.clear(); apply() }
    private fun apply() {
        val now = SystemClock.elapsedRealtime()
        val connected = room.state == Room.State.CONNECTED
        room.remoteParticipants.values.forEach { participant ->
            val identity = participant.identity?.value.orEmpty()
            val protected = TranslationAudioPolicy.protectedIdentity(identity)
            participant.trackPublications.values.filterIsInstance<RemoteTrackPublication>().forEach { publication ->
                val allowed = TranslationAudioPolicy.ordinary(identity, participant.kind != Participant.Kind.UNKNOWN) || protected && connected && grants.values.flatten().any { grant ->
                    TranslationAudioPolicy.allows(grant, now, room.sid?.sid, room.localParticipant.sid.value,
                        identity, participant.sid.value, publication.sid, participant.kind == Participant.Kind.AGENT)
                }
                // Mute before unsubscribing, including tracks whose subscription completed after revocation.
                if (protected) (publication.track as? RemoteAudioTrack)?.setVolume(if (allowed) 1.0 else 0.0)
                if (publication.isDesired != allowed) publication.setSubscribed(allowed)
            }
        }
    }
    override fun close() { clear(); scope.cancel() }
}
