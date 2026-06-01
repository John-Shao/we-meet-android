package com.we.meet.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.R
import io.livekit.android.compose.ui.ScaleType
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.Room

/** Active-speaker border color (green). */
private val SpeakingColor = Color(0xFF00C853)

/**
 * Tile background for both the video-on and camera-off states. Fixed to
 * the Tencent-Meeting-style dark gray rather than theming via
 * [MaterialTheme.colorScheme.surfaceVariant] — the theme-derived value is
 * near-white in light mode, which looks wrong behind a video call (and
 * makes the avatar placeholder's white Person icon glare out).
 */
private val TileBackground = Color(0xFF2C3033)

/**
 * Renders one participant's video tile.  When the participant has no
 * camera track (camera off, joining, etc.) it shows a "no video" placeholder
 * with their name.
 *
 * The mic-off badge is overlaid on the bottom-left so the local user has
 * confirmation that their own mute toggled.
 *
 * When [isSpeaking] is true, a 2dp green border is drawn on the tile.
 * When [showPinButton] is true, a pin icon is shown at the top-right; tapping
 * it invokes [onPinClick].
 */
@Composable
fun ParticipantTile(
    room: Room,
    participant: ParticipantUi,
    modifier: Modifier = Modifier,
    showPinButton: Boolean = false,
    isPinned: Boolean = false,
    onPinClick: (() -> Unit)? = null,
    /**
     * Invoked by the local-sharer placeholder tile's Stop button. The tile
     * only renders that button when [ParticipantUi.isScreenShare] &&
     * [ParticipantUi.isLocal] && [ParticipantUi.videoTrack] is null, so
     * passing this for any other tile is a no-op.
     */
    onStopScreenShare: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(TileBackground)
            .then(
                if (participant.isSpeaking) Modifier.border(2.dp, SpeakingColor, shape)
                else Modifier
            ),
    ) {
        val track = participant.videoTrack
        if (track != null) {
            // Screen-share tiles: letterbox with FitInside so the whole shared
            // frame is visible (cropping a slide deck or an app UI would be
            // worse than black bars). Camera tiles: Fill (default) so faces
            // aren't letterboxed — matches Tencent Meeting's behaviour.
            // Never mirror a screen-share frame even if it's local: what the
            // sharer sees on their own device must match what remotes see.
            VideoTrackView(
                videoTrack = track,
                modifier = Modifier.fillMaxSize(),
                passedRoom = room,
                mirror = participant.isLocal && !participant.isScreenShare,
                scaleType = if (participant.isScreenShare) ScaleType.FitInside else ScaleType.Fill,
            )
        } else if (participant.isScreenShare && participant.isLocal) {
            // Local-sharer placeholder. We can't draw the real capture here —
            // MediaProjection captures this very UI, so rendering the track
            // would recursively re-capture itself (hall-of-mirrors). Static
            // Compose content only → no recursion. RoomViewModel suppresses
            // the VideoTrack specifically for the isLocal screen-share tile.
            //
            // Visual style matches the More sheet's "停止共享" button (same
            // [ControlButton]): red icon inside a surfaceVariant circle,
            // label below. A single entry for "how to stop sharing" reads
            // the same wherever the user looks for it.
            val stopCallback = onStopScreenShare
            if (stopCallback != null) {
                val stopTint = Color(0xFFFF4444)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ControlButton(
                        icon = Icons.AutoMirrored.Filled.StopScreenShare,
                        label = stringResource(R.string.room_screen_share_stop),
                        isOn = true,
                        onClick = stopCallback,
                        labelColor = stopTint,
                        iconBgColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconTintColor = stopTint,
                    )
                }
            }
        } else {
            // Camera-off placeholder: circular default avatar on the tile's
            // dark-gray background, matching ProfileScreen's avatar style.
            // BoxWithConstraints is safe here — no SurfaceView in this branch
            // (the memory rule about avoiding it around VideoTrackView stands
            // for the other branch).
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val side = (minOf(maxWidth, maxHeight) * 0.30f)
                    .coerceIn(48.dp, 120.dp)
                Box(
                    modifier = Modifier
                        .size(side)
                        .clip(CircleShape)
                        .background(Color(0xFF3366FF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(side * 0.6f),
                    )
                }
            }
        }

        // Bottom overlay: name + mic state (or a share icon for
        // screen-share tiles, where the mic status belongs to the camera tile
        // and not the synthetic share tile). Skipped on the self-sharing
        // placeholder — it already renders a big centered "你正在共享屏幕"
        // label, an extra bottom-corner copy is redundant noise.
        val suppressOverlay = participant.isScreenShare && participant.isLocal &&
            participant.videoTrack == null
        if (!suppressOverlay) Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (participant.isScreenShare) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    imageVector = if (participant.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (participant.isMicEnabled) Color.White else Color(0xFFFF6B6B),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = participant.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
            )
        }

        // Top-left: raised-hand badge. Only shown for camera tiles (the
        // screen-share synthetic tile inherits the sharer's attributes but
        // visually doubling the hand badge across both tiles is noise).
        // Same orange as the More sheet's "已举手" tint so the meaning is
        // consistent across surfaces.
        if (participant.handRaisedAt != null && !participant.isScreenShare) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFB300)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = stringResource(R.string.room_tile_hand_raised),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Top-right: pin / unpin button. 44dp transparent tap target wraps a
        // 32dp visible circle so the hit area meets accessibility minimums
        // while the visual footprint stays small. zIndex(1f) keeps it above
        // the video SurfaceView for hit testing.
        val pinCallback = onPinClick
        if (showPinButton && pinCallback != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(1f)
                    .padding(4.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { pinCallback() }
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (isPinned) R.string.room_unpin_participant
                        else R.string.room_pin_participant
                    ),
                    tint = if (isPinned) SpeakingColor else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
