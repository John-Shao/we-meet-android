package com.we.meet.feature.assistant.aicall.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.feature.assistant.aicall.rtc.OmniAoqClient
import com.we.meet.feature.assistant.aicall.rtc.OmniCallClient
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon

/** Both transports preview original camera frames, independently of the model input cadence. */
@Composable
fun VideoPreview(client: OmniCallClient?, mirror: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val egl = when (client) {
        is OmniAoqClient -> client.eglContext
        is OmniWebRtcClient -> client.eglContext
        else -> null
    } ?: return
    // EGL getters may return fresh wrappers for the same native context.
    // Each client keeps its native sharing context alive for the whole call.
    val renderer = remember(client) {
        TextureViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier, update = { it.setMirror(mirror) })
    DisposableEffect(client, renderer) {
        when (client) {
            is OmniAoqClient -> client.attachPreview(renderer)
            is OmniWebRtcClient -> client.attachPreview(renderer)
        }
        onDispose {
            when (client) {
                is OmniAoqClient -> client.detachPreview(renderer)
                is OmniWebRtcClient -> client.detachPreview(renderer)
            }
            renderer.release()
        }
    }
}
