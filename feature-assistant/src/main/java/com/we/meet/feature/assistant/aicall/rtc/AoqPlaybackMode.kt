package com.we.meet.feature.assistant.aicall.rtc

import android.media.AudioManager
import com.we.meet.feature.assistant.BuildConfig

/** AOQ uses the SDK default media path; debug builds may compare the former VoIP path. */
object AoqPlaybackMode {
    val media: Boolean get() = BuildConfig.AOQ_MEDIA_PLAYBACK
    val voip: Boolean get() = !media
    val volumeStream: Int get() = if (media) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
}
