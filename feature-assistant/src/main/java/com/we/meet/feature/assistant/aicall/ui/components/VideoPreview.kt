package com.we.meet.feature.assistant.aicall.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon

/** Texture preview keeps Compose controls above the direct WebRTC camera. */
@Composable
fun VideoPreview(client: com.we.meet.feature.assistant.aicall.rtc.OmniCallClient?, mirror: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (client is com.we.meet.feature.assistant.aicall.rtc.OmniAoqClient) {
        val view = remember(client) { android.view.TextureView(context) }
        AndroidView(factory = { view }, modifier = modifier)
        DisposableEffect(client, view) {
            client.attachPreview(view)
            onDispose { client.detachPreview() }
        }
        return
    }
    val webRtc = client as? OmniWebRtcClient ?: return
    val egl = webRtc.eglContext ?: return
    val renderer = remember(client) {
        TextureViewRenderer(context).apply {
            init(egl, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier, update = { it.setMirror(mirror) })
    DisposableEffect(client, renderer) {
        webRtc.attachPreview(renderer)
        onDispose {
            webRtc.detachPreview(renderer)
            renderer.release()
        }
    }
}
