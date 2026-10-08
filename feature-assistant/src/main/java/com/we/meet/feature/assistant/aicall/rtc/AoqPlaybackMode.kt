package com.we.meet.feature.assistant.aicall.rtc

import android.media.AudioManager
import com.we.meet.feature.assistant.BuildConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioCaptureConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioPlaybackConfig

/** Capture/playback must agree: the SDK uses the first configured VoIP mode for both. */
object AoqPlaybackMode {
    val media: Boolean get() = BuildConfig.AOQ_MEDIA_PLAYBACK
    val voip: Boolean get() = !media
    val volumeStream: Int get() = if (media) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL

    fun playbackConfig() = AoqAudioPlaybackConfig().apply {
        channel = 1; isExternal = false; isDefaultSpeaker = true; isVoipMode = voip
    }

    fun captureConfig() = AoqAudioCaptureConfig().apply {
        channel = 1; isExternal = false; isVoipMode = voip
    }
}
