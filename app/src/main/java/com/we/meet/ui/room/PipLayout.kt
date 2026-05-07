package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import io.livekit.android.room.Room

/**
 * Rendered inside the Picture-in-Picture window. Shows a single tile for
 * the current speaker — remote first, falling back to local self-view —
 * so the user keeps a glance of the meeting while the App is on the
 * home screen. Toolbars and gallery are intentionally omitted: the PiP
 * window is too small for them to be legible.
 *
 * [ParticipantTile] already handles the "camera off → avatar placeholder"
 * branch, so we don't need to special-case it here.
 */
@Composable
fun PipLayout(room: Room, state: RoomUiState) {
    val speaker = state.participants.firstOrNull { it.isSpeaking && !it.isLocal }
        ?: state.participants.firstOrNull { it.isSpeaking }
        ?: state.participants.firstOrNull { !it.isLocal }
        ?: state.participants.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        speaker?.let {
            // Rectangle shape (no rounded corners): the PiP window already has
            // its own system-drawn rounded corners; letting the tile draw a
            // second, tighter 12dp radius creates black slivers in the four
            // corners where the tile is clipped but the outer Box background
            // shows through.
            ParticipantTile(
                room = room,
                participant = it,
                modifier = Modifier.fillMaxSize(),
                showPinButton = false,
                shape = RectangleShape,
            )
        }
    }
}
