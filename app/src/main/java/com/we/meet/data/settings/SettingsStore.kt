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

    private val _themeMode = MutableStateFlow(loadThemeMode())

    /**
     * Light/dark theme preference. Default is [ThemeMode.SYSTEM] which
     * defers to `isSystemInDarkTheme()` so existing installs (no
     * persisted key) follow the device setting just like before this
     * preference was introduced.
     */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    private fun loadThemeMode(): ThemeMode =
        ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))

    private val _imReminderEntry =
        MutableStateFlow(prefs.getBoolean(KEY_IM_REMINDER_ENTRY, true))

    /** P8「在消息列表提醒日程」(对标飞书,默认开):会话列表日程提醒入口。 */
    val imReminderEntry: StateFlow<Boolean> = _imReminderEntry.asStateFlow()

    fun setImReminderEntry(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IM_REMINDER_ENTRY, enabled).apply()
        _imReminderEntry.value = enabled
    }

    private val _calendarWeekStart = MutableStateFlow(
        CalendarWeekStart.fromKey(prefs.getString(KEY_CALENDAR_WEEK_START, null)),
    )

    /** P8 日历设置:每周的第一天(默认周一,保持既有视图行为)。 */
    val calendarWeekStart: StateFlow<CalendarWeekStart> = _calendarWeekStart.asStateFlow()

    fun setCalendarWeekStart(value: CalendarWeekStart) {
        prefs.edit().putString(KEY_CALENDAR_WEEK_START, value.name).apply()
        _calendarWeekStart.value = value
    }

    private val _calendarDefaultDurationMin = MutableStateFlow(
        prefs.getInt(KEY_CALENDAR_DEFAULT_DURATION, 60),
    )

    /** P8 日历设置:新建日程默认时长(分钟,默认 60,保持既有表单行为)。 */
    val calendarDefaultDurationMin: StateFlow<Int> = _calendarDefaultDurationMin.asStateFlow()

    fun setCalendarDefaultDurationMin(minutes: Int) {
        prefs.edit().putInt(KEY_CALENDAR_DEFAULT_DURATION, minutes).apply()
        _calendarDefaultDurationMin.value = minutes
    }

    private val _calendarDefaultReminderMin = MutableStateFlow(
        prefs.getInt(KEY_CALENDAR_DEFAULT_REMINDER, 10),
    )

    /** P8 日历设置:新建日程默认提醒提前量(分钟,-1 = 不提醒,默认 10)。 */
    val calendarDefaultReminderMin: StateFlow<Int> = _calendarDefaultReminderMin.asStateFlow()

    fun setCalendarDefaultReminderMin(minutes: Int) {
        prefs.edit().putInt(KEY_CALENDAR_DEFAULT_REMINDER, minutes).apply()
        _calendarDefaultReminderMin.value = minutes
    }

    private val _calendarDimPast = MutableStateFlow(
        prefs.getBoolean(KEY_CALENDAR_DIM_PAST, true),
    )

    /** P8 日历设置:降低已结束日程的亮度(对标飞书,默认开)。 */
    val calendarDimPast: StateFlow<Boolean> = _calendarDimPast.asStateFlow()

    fun setCalendarDimPast(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_DIM_PAST, enabled).apply()
        _calendarDimPast.value = enabled
    }

    private val _calendarShowWeekend = MutableStateFlow(
        prefs.getBoolean(KEY_CALENDAR_SHOW_WEEKEND, true),
    )

    /** P8 日历设置:周视图是否显示周末(默认开 → 显示整周;关则只看周一~周五
     *  工作周,对标 Google/飞书「显示周末」;仅作用于周视图列,不影响其它视图)。 */
    val calendarShowWeekend: StateFlow<Boolean> = _calendarShowWeekend.asStateFlow()

    fun setCalendarShowWeekend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_SHOW_WEEKEND, enabled).apply()
        _calendarShowWeekend.value = enabled
    }

    private companion object {
        const val FILE_NAME = "jusi_meet_settings"
        const val KEY_VIDEO_CODEC = "video_codec"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_IM_REMINDER_ENTRY = "im_reminder_entry"
        const val KEY_CALENDAR_WEEK_START = "calendar_week_start"
        const val KEY_CALENDAR_DEFAULT_DURATION = "calendar_default_duration"
        const val KEY_CALENDAR_DEFAULT_REMINDER = "calendar_default_reminder"
        const val KEY_CALENDAR_DIM_PAST = "calendar_dim_past"
        const val KEY_CALENDAR_SHOW_WEEKEND = "calendar_show_weekend"
    }
}

/** P8 日历设置:每周的第一天。 */
enum class CalendarWeekStart {
    MONDAY,
    SUNDAY;

    companion object {
        fun fromKey(key: String?): CalendarWeekStart =
            entries.firstOrNull { it.name == key } ?: MONDAY
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.name == key } ?: DEFAULT
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
