package com.we.meet.ui.room

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.livekit.android.room.participant.Participant
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.map

@Composable
internal fun rememberParticipantSid(participant: Participant): Participant.Sid {
    // Participant.sid is a plain property in LiveKit 2.24.1, not @FlowObservable.
    // participantInfo is observable and is updated on join/rejoin and cleared on dispose.
    val infoFlow = participant::participantInfo.flow
    val sidFlow = remember(infoFlow) { infoFlow.map { participant.sid } }
    val sid by sidFlow.collectAsStateWithLifecycle(initialValue = participant.sid)
    return sid
}
