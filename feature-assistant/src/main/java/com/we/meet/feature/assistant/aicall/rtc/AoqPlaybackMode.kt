package com.we.meet.feature.assistant.aicall.rtc

import android.media.AudioManager
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioCaptureConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioPlaybackConfig
import com.we.meet.feature.assistant.aicall.model.AiCallAudioMode

/**
 * Capture/playback must agree: the SDK uses the first configured VoIP mode for both.
 *
 * VoIP mode is what turns on the hardware AEC that keeps the assistant from hearing
 * its own speaker output; media mode is louder but has no such reference.
 */
object AoqPlaybackMode {
    fun volumeStream(mode: AiCallAudioMode): Int =
        if (mode.voip) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    fun playbackConfig(mode: AiCallAudioMode) = AoqAudioPlaybackConfig().apply {
        channel = 1; isExternal = false; isDefaultSpeaker = true; isVoipMode = mode.voip
    }

    fun captureConfig(mode: AiCallAudioMode) = AoqAudioCaptureConfig().apply {
        channel = 1; isExternal = false; isVoipMode = mode.voip
    }
}
