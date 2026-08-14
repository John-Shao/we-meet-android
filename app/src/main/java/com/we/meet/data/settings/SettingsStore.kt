package com.we.meet.data.settings

import android.content.Context
import android.util.Log
import com.we.meet.data.api.CalendarApi
import com.we.meet.data.api.dto.CalendarPreferenceDto
import io.livekit.android.room.track.VideoCodec
import java.security.MessageDigest
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

/** Calendar root view. This is intentionally local-only: it is a navigation
 * preference, not part of the cross-device calendar rendering contract. */
enum class CalendarDisplayMode {
    AGENDA,
    DAY,
    MULTI_DAY,
    MONTH;

    companion object {
        fun fromKey(key: String?): CalendarDisplayMode =
            entries.firstOrNull { it.name == key } ?: MULTI_DAY
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

class SettingsStore(
    context: Context,
    private val calendarAccountKeyProvider: () -> String? = { null },
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferenceMutex = Mutex()
    @Volatile private var calendarApi: CalendarApi? = null
    @Volatile private var localCalendarGeneration =
        if (prefs.getBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, false)) 1L else 0L
    @Volatile private var syncedCalendarGeneration = 0L
    @Volatile private var calendarAccountGeneration = 0L

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
        if (!activateCalendarAccount()) return
        prefs.edit().putBoolean(KEY_IM_REMINDER_ENTRY, enabled).apply()
        _imReminderEntry.value = enabled
    }

    private val _calendarWeekStart = MutableStateFlow(
        CalendarWeekStart.fromKey(prefs.getString(KEY_CALENDAR_WEEK_START, null)),
    )

    /** P8 日历设置:每周的第一天(默认周一,保持既有视图行为)。 */
    val calendarWeekStart: StateFlow<CalendarWeekStart> = _calendarWeekStart.asStateFlow()

    fun setCalendarWeekStart(value: CalendarWeekStart) {
        if (!activateCalendarAccount()) return
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
        if (!activateCalendarAccount()) return
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
        if (!activateCalendarAccount()) return
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
        if (!activateCalendarAccount()) return
        prefs.edit().putBoolean(KEY_CALENDAR_DIM_PAST, enabled).apply()
        _calendarDimPast.value = enabled
        calendarPreferenceChanged()
    }

    private val _calendarDisplayMode = MutableStateFlow(
        CalendarDisplayMode.fromKey(prefs.getString(KEY_CALENDAR_DISPLAY_MODE, null)),
    )
    val calendarDisplayMode: StateFlow<CalendarDisplayMode> =
        _calendarDisplayMode.asStateFlow()

    fun setCalendarDisplayMode(mode: CalendarDisplayMode) {
        if (!activateCalendarAccount()) return
        prefs.edit().putString(KEY_CALENDAR_DISPLAY_MODE, mode.name).apply()
        _calendarDisplayMode.value = mode
    }

    private val _workingHours = MutableStateFlow(loadWorkingHours())

    /** User-local working window shared by calendar, free/busy and meeting rooms. */
    val workingHours: StateFlow<WorkingHours> = _workingHours.asStateFlow()

    /** Persist both endpoints in one edit so observers never see a mixed pair. */
    fun setWorkingHours(startMin: Int, endMin: Int): Boolean {
        if (!isValidWorkingHours(startMin, endMin)) return false
        if (!activateCalendarAccount()) return false
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
        if (!activateCalendarAccount()) return
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
        if (!activateCalendarAccount()) return
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
        if (!activateCalendarAccount()) return
        prefs.edit().putString(KEY_CALENDAR_TIMEZONE_MODE, mode.name).apply()
        _calendarTimezoneMode.value = mode
        calendarPreferenceChanged()
    }

    fun setCalendarFixedTimezone(value: String): Boolean {
        val zone = runCatching { ZoneId.of(value) }.getOrNull() ?: return false
        if (!activateCalendarAccount()) return false
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
        if (!activateCalendarAccount()) return
        preferenceScope.launch { synchronizeCalendarPreferencesNow() }
    }

    private fun calendarPreferenceChanged() {
        if (!activateCalendarAccount()) return
        prefs.edit().putBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, true).apply()
        localCalendarGeneration += 1
        synchronizeCalendarPreferences()
    }

    /**
     * Calendar preferences belong to an account, while codec/theme preferences
     * remain device-wide.  A new account must never inherit the previous
     * account's offline cache before its server copy has loaded.
     */
    private fun activateCalendarAccount(): Boolean {
        val accountKey = calendarAccountKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(::calendarAccountCacheKey) ?: return false
        synchronized(this) {
            if (prefs.getString(KEY_CALENDAR_CACHE_ACCOUNT, null) == accountKey) return true
            val editor = prefs.edit()
            CALENDAR_ACCOUNT_KEYS.forEach(editor::remove)
            editor.putString(KEY_CALENDAR_CACHE_ACCOUNT, accountKey).commit()

            _imReminderEntry.value = true
            _calendarWeekStart.value = CalendarWeekStart.MONDAY
            _calendarDefaultDurationMin.value = 60
            _calendarDefaultReminderMin.value = 10
            _calendarDimPast.value = true
            _calendarDisplayMode.value = CalendarDisplayMode.MULTI_DAY
            _workingHours.value = WorkingHours()
            _calendarTimeRangeMode.value = TimeRangeMode.WORK
            _meetingRoomTimeRangeMode.value = TimeRangeMode.WORK
            _calendarTimezoneMode.value = CalendarTimezoneMode.AUTO
            _calendarFixedTimezone.value = ZoneId.systemDefault().id
            localCalendarGeneration = 0L
            syncedCalendarGeneration = 0L
            calendarAccountGeneration += 1
        }
        return true
    }

    private suspend fun synchronizeCalendarPreferencesNow() {
        val api = calendarApi ?: return
        val accountGeneration = calendarAccountGeneration
        preferenceMutex.withLock {
            try {
                val remote = api.getCalendarPreference()
                if (accountGeneration != calendarAccountGeneration) return@withLock
                val dirty = prefs.getBoolean(KEY_CALENDAR_PREFERENCE_DIRTY, false)
                val baseRevision = prefs.getInt(KEY_CALENDAR_PREFERENCE_REVISION, -1)
                val changedLocally = localCalendarGeneration > syncedCalendarGeneration
                if (
                    !remote.initialized ||
                    ((dirty || changedLocally) && baseRevision == remote.revision)
                ) {
                    pushCalendarPreference(api, remote.revision, accountGeneration)
                } else {
                    applyCalendarPreference(remote)
                    syncedCalendarGeneration = localCalendarGeneration
                }
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 409) {
                    val latest = runCatching { api.getCalendarPreference() }.getOrNull()
                    if (latest != null && accountGeneration == calendarAccountGeneration) {
                        applyCalendarPreference(latest)
                        syncedCalendarGeneration = localCalendarGeneration
                        return@withLock
                    }
                }
                Log.w(TAG, "Calendar preference sync failed; keeping local cache", error)
            }
        }
    }

    private suspend fun pushCalendarPreference(
        api: CalendarApi,
        expectedRevision: Int,
        accountGeneration: Long,
    ) {
        val sentGeneration = localCalendarGeneration
        val saved = api.updateCalendarPreference(calendarPreferenceJson(expectedRevision))
        if (accountGeneration != calendarAccountGeneration) return
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
        // The fixed three-day view always includes weekends. Keep this legacy
        // server field for wire compatibility while removing the client option.
        put("show_weekend", true)
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

    private fun calendarAccountCacheKey(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val FILE_NAME = "jusi_meet_settings"
        const val KEY_VIDEO_CODEC = "video_codec"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_IM_REMINDER_ENTRY = "im_reminder_entry"
        const val KEY_CALENDAR_WEEK_START = "calendar_week_start"
        const val KEY_CALENDAR_DEFAULT_DURATION = "calendar_default_duration"
        const val KEY_CALENDAR_DEFAULT_REMINDER = "calendar_default_reminder"
        const val KEY_CALENDAR_DIM_PAST = "calendar_dim_past"
        const val KEY_CALENDAR_DISPLAY_MODE = "calendar_display_mode"
        const val KEY_WORKING_HOURS_START = "calendar_working_hours_start"
        const val KEY_WORKING_HOURS_END = "calendar_working_hours_end"
        const val KEY_CALENDAR_TIME_RANGE_MODE = "calendar_time_range_mode"
        const val KEY_MEETING_ROOM_TIME_RANGE_MODE = "meeting_room_time_range_mode"
        const val KEY_CALENDAR_TIMEZONE_MODE = "calendar_timezone_mode"
        const val KEY_CALENDAR_FIXED_TIMEZONE = "calendar_fixed_timezone"
        const val KEY_CALENDAR_PREFERENCE_REVISION = "calendar_preference_revision"
        const val KEY_CALENDAR_PREFERENCE_DIRTY = "calendar_preference_dirty"
        const val KEY_CALENDAR_CACHE_ACCOUNT = "calendar_cache_account"
        const val TAG = "SettingsStore"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val CALENDAR_ACCOUNT_KEYS = arrayOf(
            KEY_IM_REMINDER_ENTRY,
            KEY_CALENDAR_WEEK_START,
            KEY_CALENDAR_DEFAULT_DURATION,
            KEY_CALENDAR_DEFAULT_REMINDER,
            KEY_CALENDAR_DIM_PAST,
            KEY_CALENDAR_DISPLAY_MODE,
            KEY_WORKING_HOURS_START,
            KEY_WORKING_HOURS_END,
            KEY_CALENDAR_TIME_RANGE_MODE,
            KEY_MEETING_ROOM_TIME_RANGE_MODE,
            KEY_CALENDAR_TIMEZONE_MODE,
            KEY_CALENDAR_FIXED_TIMEZONE,
            KEY_CALENDAR_PREFERENCE_REVISION,
            KEY_CALENDAR_PREFERENCE_DIRTY,
        )
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
