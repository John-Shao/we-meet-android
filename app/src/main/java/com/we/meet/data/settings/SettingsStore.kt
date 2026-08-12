package com.we.meet.data.settings

import android.content.Context
import android.util.Log
import com.we.meet.data.api.CalendarApi
import com.we.meet.data.api.dto.CalendarPreferenceDto
import io.livekit.android.room.track.VideoCodec
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.HttpException

/**
 * User-tunable client preferences. Backed by plain SharedPreferences — these
 * values are non-sensitive UI/runtime knobs (no auth material).
 *
 * Reads through [videoCodec] always reflect the latest written value across
 * the whole app via [StateFlow], so screens that observe the setting (e.g.
 * Settings, RoomViewModel) stay in sync without polling SharedPreferences.
 */
/** 周视图「一屏显示几天」的可选范围(设置页选项与存储钳制共用同一处)。 */
val CALENDAR_WEEK_VISIBLE_DAYS_RANGE = 3..7

/** 默认 3 天(对齐飞书「三日」视图):窄屏上列宽约 105dp,块内标题读得清。 */
const val CALENDAR_WEEK_VISIBLE_DAYS_DEFAULT = 3

const val WORKING_HOURS_STEP_MIN = 30
const val WORKING_HOURS_DEFAULT_START_MIN = 9 * 60
const val WORKING_HOURS_DEFAULT_END_MIN = 18 * 60
const val WORKING_HOURS_MIN_DURATION_MIN = 6 * 60
const val WORKING_HOURS_MAX_DURATION_MIN = 12 * 60

data class WorkingHours(
    val startMin: Int = WORKING_HOURS_DEFAULT_START_MIN,
    val endMin: Int = WORKING_HOURS_DEFAULT_END_MIN,
)

enum class TimeRangeMode {
    WORK,
    FULL;

    companion object {
        fun fromKey(key: String?): TimeRangeMode =
            entries.firstOrNull { it.name == key } ?: WORK
    }
}

enum class CalendarTimezoneMode {
    AUTO,
    FIXED;

    companion object {
        fun fromKey(key: String?): CalendarTimezoneMode =
            entries.firstOrNull { it.name == key } ?: AUTO
    }
}

fun isValidWorkingHours(startMin: Int, endMin: Int): Boolean {
    val duration = endMin - startMin
    return startMin in 0 until 24 * 60 &&
        endMin in 1..24 * 60 &&
        startMin % WORKING_HOURS_STEP_MIN == 0 &&
        endMin % WORKING_HOURS_STEP_MIN == 0 &&
        duration in WORKING_HOURS_MIN_DURATION_MIN..WORKING_HOURS_MAX_DURATION_MIN
}

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferenceMutex = Mutex()
    @Volatile private var calendarApi: CalendarApi? = null
    @Volatile private var localCalendarGeneration =
        if (prefs.getBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, false)) 1L else 0L
    @Volatile private var syncedCalendarGeneration = 0L

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
        calendarPreferenceChanged()
    }

    private val _calendarDefaultDurationMin = MutableStateFlow(
        prefs.getInt(KEY_CALENDAR_DEFAULT_DURATION, 60),
    )

    /** P8 日历设置:新建日程默认时长(分钟,默认 60,保持既有表单行为)。 */
    val calendarDefaultDurationMin: StateFlow<Int> = _calendarDefaultDurationMin.asStateFlow()

    fun setCalendarDefaultDurationMin(minutes: Int) {
        prefs.edit().putInt(KEY_CALENDAR_DEFAULT_DURATION, minutes).apply()
        _calendarDefaultDurationMin.value = minutes
        calendarPreferenceChanged()
    }

    private val _calendarDefaultReminderMin = MutableStateFlow(
        prefs.getInt(KEY_CALENDAR_DEFAULT_REMINDER, 10),
    )

    /** P8 日历设置:新建日程默认提醒提前量(分钟,-1 = 不提醒,默认 10)。 */
    val calendarDefaultReminderMin: StateFlow<Int> = _calendarDefaultReminderMin.asStateFlow()

    fun setCalendarDefaultReminderMin(minutes: Int) {
        prefs.edit().putInt(KEY_CALENDAR_DEFAULT_REMINDER, minutes).apply()
        _calendarDefaultReminderMin.value = minutes
        calendarPreferenceChanged()
    }

    private val _calendarDimPast = MutableStateFlow(
        prefs.getBoolean(KEY_CALENDAR_DIM_PAST, true),
    )

    /** P8 日历设置:降低已结束日程的亮度(对标飞书,默认开)。 */
    val calendarDimPast: StateFlow<Boolean> = _calendarDimPast.asStateFlow()

    fun setCalendarDimPast(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_DIM_PAST, enabled).apply()
        _calendarDimPast.value = enabled
        calendarPreferenceChanged()
    }

    private val _calendarShowWeekend = MutableStateFlow(
        prefs.getBoolean(KEY_CALENDAR_SHOW_WEEKEND, false),
    )

    /** P8 日历设置:周视图是否显示周末。**App 默认关**(小屏聚焦周一~周五工作
     *  周;Web 大屏默认开,刻意按端差异化)。对标 Google/飞书「显示周末」;仅作用
     *  于周视图列,不影响其它视图。 */
    val calendarShowWeekend: StateFlow<Boolean> = _calendarShowWeekend.asStateFlow()

    fun setCalendarShowWeekend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_SHOW_WEEKEND, enabled).apply()
        _calendarShowWeekend.value = enabled
        calendarPreferenceChanged()
    }

    private val _calendarWeekVisibleDays = MutableStateFlow(
        prefs.getInt(KEY_CALENDAR_WEEK_VISIBLE_DAYS, CALENDAR_WEEK_VISIBLE_DAYS_DEFAULT)
            .coerceIn(CALENDAR_WEEK_VISIBLE_DAYS_RANGE),
    )

    /**
     * 周视图一屏铺几天(3~7,默认 3 = 飞书「三日」)。屏幕窄 / 想看清标题就
     * 调小,想一眼看全一周就调到 7;比一屏列数多的天数横滑看。仅作用于周视图。
     *
     * 与 [calendarShowWeekend] 是两件事:那个决定「有几列」(5 或 7),这个决定
     * 「一屏铺几列」——列数比它少时铺满不滚。
     */
    val calendarWeekVisibleDays: StateFlow<Int> = _calendarWeekVisibleDays.asStateFlow()

    fun setCalendarWeekVisibleDays(days: Int) {
        val safe = days.coerceIn(CALENDAR_WEEK_VISIBLE_DAYS_RANGE)
        prefs.edit().putInt(KEY_CALENDAR_WEEK_VISIBLE_DAYS, safe).apply()
        _calendarWeekVisibleDays.value = safe
    }

    private val _workingHours = MutableStateFlow(loadWorkingHours())

    /** User-local working window shared by calendar, free/busy and meeting rooms. */
    val workingHours: StateFlow<WorkingHours> = _workingHours.asStateFlow()

    /** Persist both endpoints in one edit so observers never see a mixed pair. */
    fun setWorkingHours(startMin: Int, endMin: Int): Boolean {
        if (!isValidWorkingHours(startMin, endMin)) return false
        prefs.edit()
            .putInt(KEY_WORKING_HOURS_START, startMin)
            .putInt(KEY_WORKING_HOURS_END, endMin)
            .apply()
        _workingHours.value = WorkingHours(startMin, endMin)
        calendarPreferenceChanged()
        return true
    }

    private fun loadWorkingHours(): WorkingHours {
        val start = prefs.getInt(KEY_WORKING_HOURS_START, WORKING_HOURS_DEFAULT_START_MIN)
        val end = prefs.getInt(KEY_WORKING_HOURS_END, WORKING_HOURS_DEFAULT_END_MIN)
        return if (isValidWorkingHours(start, end)) WorkingHours(start, end)
        else WorkingHours()
    }

    private val _calendarTimeRangeMode = MutableStateFlow(
        TimeRangeMode.fromKey(prefs.getString(KEY_CALENDAR_TIME_RANGE_MODE, null)),
    )
    val calendarTimeRangeMode: StateFlow<TimeRangeMode> =
        _calendarTimeRangeMode.asStateFlow()

    fun setCalendarTimeRangeMode(mode: TimeRangeMode) {
        prefs.edit().putString(KEY_CALENDAR_TIME_RANGE_MODE, mode.name).apply()
        _calendarTimeRangeMode.value = mode
        calendarPreferenceChanged()
    }

    private val _meetingRoomTimeRangeMode = MutableStateFlow(
        TimeRangeMode.fromKey(prefs.getString(KEY_MEETING_ROOM_TIME_RANGE_MODE, null)),
    )
    val meetingRoomTimeRangeMode: StateFlow<TimeRangeMode> =
        _meetingRoomTimeRangeMode.asStateFlow()

    fun setMeetingRoomTimeRangeMode(mode: TimeRangeMode) {
        prefs.edit().putString(KEY_MEETING_ROOM_TIME_RANGE_MODE, mode.name).apply()
        _meetingRoomTimeRangeMode.value = mode
        calendarPreferenceChanged()
    }

    private val _calendarTimezoneMode = MutableStateFlow(
        CalendarTimezoneMode.fromKey(prefs.getString(KEY_CALENDAR_TIMEZONE_MODE, null)),
    )
    val calendarTimezoneMode: StateFlow<CalendarTimezoneMode> =
        _calendarTimezoneMode.asStateFlow()

    private val _calendarFixedTimezone = MutableStateFlow(loadFixedTimezone())
    val calendarFixedTimezone: StateFlow<String> = _calendarFixedTimezone.asStateFlow()

    fun setCalendarTimezoneMode(mode: CalendarTimezoneMode) {
        prefs.edit().putString(KEY_CALENDAR_TIMEZONE_MODE, mode.name).apply()
        _calendarTimezoneMode.value = mode
        calendarPreferenceChanged()
    }

    fun setCalendarFixedTimezone(value: String): Boolean {
        val zone = runCatching { ZoneId.of(value) }.getOrNull() ?: return false
        prefs.edit().putString(KEY_CALENDAR_FIXED_TIMEZONE, zone.id).apply()
        _calendarFixedTimezone.value = zone.id
        calendarPreferenceChanged()
        return true
    }

    fun calendarZoneId(): ZoneId =
        if (_calendarTimezoneMode.value == CalendarTimezoneMode.FIXED) {
            runCatching { ZoneId.of(_calendarFixedTimezone.value) }.getOrDefault(ZoneId.systemDefault())
        } else {
            ZoneId.systemDefault()
        }

    /** Bind once at process start; calendar settings remain usable offline from this cache. */
    fun bindCalendarApi(api: CalendarApi) {
        calendarApi = api
        synchronizeCalendarPreferences()
    }

    /** Retry on calendar/settings entry after an anonymous or offline app start. */
    fun synchronizeCalendarPreferences() {
        preferenceScope.launch { synchronizeCalendarPreferencesNow() }
    }

    private fun calendarPreferenceChanged() {
        prefs.edit().putBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, true).apply()
        localCalendarGeneration += 1
        synchronizeCalendarPreferences()
    }

    private suspend fun synchronizeCalendarPreferencesNow() {
        val api = calendarApi ?: return
        preferenceMutex.withLock {
            try {
                val remote = api.getCalendarPreference()
                val dirty = prefs.getBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, false)
                val baseRevision = prefs.getInt(KEY_CALENDAR_PREFERENCE_REVISION, -1)
                val changedLocally = localCalendarGeneration > syncedCalendarGeneration
                if (
                    !remote.initialized ||
                    ((dirty || changedLocally) && baseRevision == remote.revision)
                ) {
                    pushCalendarPreference(api, remote.revision)
                } else {
                    applyCalendarPreference(remote)
                    syncedCalendarGeneration = localCalendarGeneration
                }
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 409) {
                    val latest = runCatching { api.getCalendarPreference() }.getOrNull()
                    if (latest != null) {
                        applyCalendarPreference(latest)
                        syncedCalendarGeneration = localCalendarGeneration
                        return@withLock
                    }
                }
                Log.w(TAG, "Calendar preference sync failed; keeping local cache", error)
            }
        }
    }

    private suspend fun pushCalendarPreference(api: CalendarApi, expectedRevision: Int) {
        val sentGeneration = localCalendarGeneration
        val saved = api.updateCalendarPreference(calendarPreferenceJson(expectedRevision))
        syncedCalendarGeneration = sentGeneration
        if (localCalendarGeneration == sentGeneration) {
            applyCalendarPreference(saved)
        } else {
            prefs.edit()
                .putInt(KEY_CALENDAR_PREFERENCE_REVISION, saved.revision)
                .putBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, true)
                .apply()
            synchronizeCalendarPreferences()
        }
    }

    private fun applyCalendarPreference(value: CalendarPreferenceDto) {
        val timezoneMode = if (value.timezoneMode == "fixed") {
            CalendarTimezoneMode.FIXED
        } else {
            CalendarTimezoneMode.AUTO
        }
        val fixedTimezone = value.timezone?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
            ?: _calendarFixedTimezone.value
        val weekStart = if (value.weekStart == "sun") CalendarWeekStart.SUNDAY
            else CalendarWeekStart.MONDAY
        val calendarRange = if (value.calendarTimeRange == "full") TimeRangeMode.FULL
            else TimeRangeMode.WORK
        val roomsRange = if (value.meetingRoomsTimeRange == "full") TimeRangeMode.FULL
            else TimeRangeMode.WORK
        val reminder = value.defaultReminderMinutes ?: -1
        prefs.edit()
            .putString(KEY_CALENDAR_TIMEZONE_MODE, timezoneMode.name)
            .putString(KEY_CALENDAR_FIXED_TIMEZONE, fixedTimezone)
            .putString(KEY_CALENDAR_WEEK_START, weekStart.name)
            .putInt(KEY_CALENDAR_DEFAULT_DURATION, value.defaultDurationMinutes)
            .putInt(KEY_CALENDAR_DEFAULT_REMINDER, reminder)
            .putBoolean(KEY_CALENDAR_DIM_PAST, value.dimPast)
            .putBoolean(KEY_CALENDAR_SHOW_WEEKEND, value.showWeekend)
            .putInt(KEY_WORKING_HOURS_START, value.workingStartMinutes)
            .putInt(KEY_WORKING_HOURS_END, value.workingEndMinutes)
            .putString(KEY_CALENDAR_TIME_RANGE_MODE, calendarRange.name)
            .putString(KEY_MEETING_ROOM_TIME_RANGE_MODE, roomsRange.name)
            .putInt(KEY_CALENDAR_PREFERENCE_REVISION, value.revision)
            .putBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, false)
            .apply()
        _calendarTimezoneMode.value = timezoneMode
        _calendarFixedTimezone.value = fixedTimezone
        _calendarWeekStart.value = weekStart
        _calendarDefaultDurationMin.value = value.defaultDurationMinutes
        _calendarDefaultReminderMin.value = reminder
        _calendarDimPast.value = value.dimPast
        _calendarShowWeekend.value = value.showWeekend
        _workingHours.value = WorkingHours(value.workingStartMinutes, value.workingEndMinutes)
        _calendarTimeRangeMode.value = calendarRange
        _meetingRoomTimeRangeMode.value = roomsRange
    }

    private fun calendarPreferenceJson(expectedRevision: Int) = JSONObject().apply {
        put("timezone_mode", _calendarTimezoneMode.value.name.lowercase())
        put(
            "timezone",
            if (_calendarTimezoneMode.value == CalendarTimezoneMode.FIXED) {
                _calendarFixedTimezone.value
            } else {
                JSONObject.NULL
            },
        )
        put("week_start", if (_calendarWeekStart.value == CalendarWeekStart.SUNDAY) "sun" else "mon")
        put("default_duration_minutes", _calendarDefaultDurationMin.value)
        put(
            "default_reminder_minutes",
            _calendarDefaultReminderMin.value.takeIf { it >= 0 } ?: JSONObject.NULL,
        )
        put("dim_past", _calendarDimPast.value)
        put("show_weekend", _calendarShowWeekend.value)
        put("working_start_minutes", _workingHours.value.startMin)
        put("working_end_minutes", _workingHours.value.endMin)
        put("calendar_time_range", _calendarTimeRangeMode.value.name.lowercase())
        put("meeting_rooms_time_range", _meetingRoomTimeRangeMode.value.name.lowercase())
        put("expected_revision", expectedRevision)
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun loadFixedTimezone(): String {
        val stored = prefs.getString(KEY_CALENDAR_FIXED_TIMEZONE, null)
        return stored?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
            ?: ZoneId.systemDefault().id
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
        const val KEY_CALENDAR_WEEK_VISIBLE_DAYS = "calendar_week_visible_days"
        const val KEY_WORKING_HOURS_START = "calendar_working_hours_start"
        const val KEY_WORKING_HOURS_END = "calendar_working_hours_end"
        const val KEY_CALENDAR_TIME_RANGE_MODE = "calendar_time_range_mode"
        const val KEY_MEETING_ROOM_TIME_RANGE_MODE = "meeting_room_time_range_mode"
        const val KEY_CALENDAR_TIMEZONE_MODE = "calendar_timezone_mode"
        const val KEY_CALENDAR_FIXED_TIMEZONE = "calendar_fixed_timezone"
        const val KEY_CALENDAR_PREFERENCE_REVISION = "calendar_preference_revision"
        const val KEY_CALENDAR_PREFERENCE_DIRTY = "calendar_preference_dirty"
        const val TAG = "SettingsStore"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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
