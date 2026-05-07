package com.we.meet.data.settings

import android.content.Context
import io.livekit.android.room.track.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-tunable client preferences. Backed by plain SharedPreferences — these
 * values are non-sensitive UI/runtime knobs (no auth material).
 *
 * Reads through [videoCodec] always reflect the latest written value across
 * the whole app via [StateFlow], so screens that observe the setting (e.g.
 * Settings, RoomViewModel) stay in sync without polling SharedPreferences.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _videoCodec = MutableStateFlow(loadVideoCodec())

    /** Current video-codec preference. Hot StateFlow — emits on every change. */
    val videoCodec: StateFlow<VideoCodecPref> = _videoCodec.asStateFlow()

    fun setVideoCodec(pref: VideoCodecPref) {
        prefs.edit().putString(KEY_VIDEO_CODEC, pref.name).apply()
        _videoCodec.value = pref
    }

    private fun loadVideoCodec(): VideoCodecPref =
        VideoCodecPref.fromKey(prefs.getString(KEY_VIDEO_CODEC, null))

    private companion object {
        const val FILE_NAME = "jusi_meet_settings"
        const val KEY_VIDEO_CODEC = "video_codec"
    }
}

/**
 * Codecs we expose in Settings. The string the LiveKit SDK actually publishes
 * with comes from [VideoCodec.codecName] — we round-trip through that so we
 * stay in sync with whatever the SDK negotiates on the wire.
 */
enum class VideoCodecPref(
    val sdkCodec: VideoCodec,
    /** Human-readable label rendered in Settings. Not localised — codec names
     *  are conventional across locales. */
    val displayLabel: String,
) {
    H264(VideoCodec.H264, "H.264"),
    H265(VideoCodec.H265, "H.265"),
    VP8(VideoCodec.VP8, "VP8"),
    VP9(VideoCodec.VP9, "VP9");

    companion object {
        val DEFAULT: VideoCodecPref = H264

        fun fromKey(key: String?): VideoCodecPref =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
