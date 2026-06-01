package com.we.meet.feature.assistant.aicall.ui.components

import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

private class TextureRendererHolder {
    var container: FrameLayout? = null
    var renderer: TextureViewRenderer? = null
    var initializedRoom: Room? = null
    var attachedTrack: VideoTrack? = null
}

/**
 * Renders a local LiveKit [VideoTrack] using [TextureViewRenderer].
 *
 * TextureView is chosen over SurfaceView so the preview can sit beneath
 * Compose-drawn overlays (controls, status text) without z-order issues.
 */
@Composable
fun VideoPreview(
    room: Room?,
    track: VideoTrack?,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    val holder = remember { TextureRendererHolder() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).also { container ->
                container.setBackgroundColor(Color.BLACK)
                val renderer = TextureViewRenderer(ctx)
                container.addView(
                    renderer,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                holder.container = container
                holder.renderer = renderer
            }
        },
        update = {
            val renderer = holder.renderer ?: return@AndroidView

            if (room != null && holder.initializedRoom !== room) {
                room.initVideoRenderer(renderer)
                holder.initializedRoom = room
            }

            renderer.setMirror(mirror)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

            if (holder.attachedTrack !== track) {
                holder.attachedTrack?.removeRenderer(renderer)
                if (holder.initializedRoom != null && track != null) {
                    track.addRenderer(renderer)
                }
                holder.attachedTrack = track
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            val renderer = holder.renderer
            val attached = holder.attachedTrack
            if (renderer != null && attached != null) {
                attached.removeRenderer(renderer)
            }
            holder.attachedTrack = null
            holder.initializedRoom = null
            renderer?.release()
            holder.renderer = null
            holder.container = null
        }
    }
}
