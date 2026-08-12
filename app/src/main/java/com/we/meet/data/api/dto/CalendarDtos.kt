package com.we.meet.data.api.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Calendar DTOs — mirror of the web client's ApiCalendar.ts / core/api/calendar.py.
 * Everything except `id` is defaulted/nullable (Moshi reflection throws on
 * missing non-null fields).
 */

@JsonClass(generateAdapter = true)
data class EventAttendeeDto(
    val id: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    // ⚠️ server sends explicit nulls here; Moshi defaults only cover ABSENT keys,
    // so these must be nullable types, not non-null-with-default.
    val email: String? = null,
    /** needs_action | accepted | declined | tentative */
    val rsvp: String? = null,
    /** organizer | required | optional */
    val role: String? = null,
)

@JsonClass(generateAdapter = true)
data class AttendeeEntryRequest(
    @Json(name = "user_id") val userId: String? = null,
    val email: String? = null,
    /** required | optional */
    val role: String = "required",
)

/** P8 忙闲:一个 busy 区间(ISO 8601 UTC,已按窗口裁剪、重叠合并)。 */
@JsonClass(generateAdapter = true)
data class BusyIntervalDto(
    val start: String = "",
    val end: String = "",
)

@JsonClass(generateAdapter = true)
data class FreeBusyEntryDto(
    @Json(name = "user_id") val userId: String = "",
    val busy: List<BusyIntervalDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class FreeBusyResponseDto(
    val results: List<FreeBusyEntryDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class EventOrganizerDto(
    val id: String = "",
    @Json(name = "full_name") val fullName: String? = null,
)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    /** ISO 8601 (UTC). */
    @Json(name = "start_at") val startAt: String = "",
    @Json(name = "end_at") val endAt: String = "",
    /** IANA zone the event was authored in — drives all-day date math. */
    val timezone: String = "UTC",
    @Json(name = "all_day") val allDay: Boolean = false,
    val status: String = "",
    val visibility: String = "",
    @Json(name = "details_redacted") val detailsRedacted: Boolean = false,
    /** Minutes-before offsets. */
    val reminders: List<Int> = emptyList(),
    val organizer: EventOrganizerDto? = null,
    /** Room id (join target) + slug; null when the event has no room. */
    val room: String? = null,
    @Json(name = "room_slug") val roomSlug: String? = null,
    /**
     * P9 实体会议室 —— 与上面的 LiveKit `room` 毫无关系。null = 未预订。
     * `bookingStatus == "conflict"` 表示该场次没抢到房间(重复日程滚动物化
     * 时可能发生):会议照开,只是没订上会议室。
     */
    @Json(name = "meeting_room") val meetingRoom: MeetingRoomBriefDto? = null,
    val attendees: List<EventAttendeeDto> = emptyList(),
    @Json(name = "my_rsvp") val myRsvp: String? = null,
    @Json(name = "created_at") val createdAt: String = "",
    // P2-M2 重复日程:主事件带 RRULE(recurrence 非空),子场次 recurrence_parent
    // 指回主事件。App 端据此识别重复日程——编辑/删除的三选语义(仅此次/此次及
    // 以后/全部)尚未落地(roadmap M3),故检测到重复即引导用户到网页端管理,
    // 避免无 edit_scope 的请求被后端静默按「全部/仅此次」处理。
    val recurrence: String = "",
    @Json(name = "recurrence_parent") val recurrenceParent: String? = null,
) {
    /** 主事件(带 RRULE)或已物化的子场次——两者的编辑/删除都受三选语义影响。 */
    val isRecurring: Boolean
        get() = recurrence.isNotBlank() || recurrenceParent != null
}

@JsonClass(generateAdapter = true)
data class PagedCalendarEventsDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<CalendarEventDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreateEventRequest(
    val title: String,
    @Json(name = "start_at") val startAt: String,
    @Json(name = "end_at") val endAt: String,
    @Json(name = "all_day") val allDay: Boolean = false,
    val reminders: List<Int> = emptyList(),
    @Json(name = "attendee_ids") val attendeeIds: List<String>? = null,
    @Json(name = "attendee_entries") val attendeeEntries: List<AttendeeEntryRequest>? = null,
    val description: String = "",
    val visibility: String = "default",
    val timezone: String,
    /** P2-M3 重复日程:RRULE 串(UNTIL 用浮动本地时刻),空=单次。 */
    val recurrence: String = "",
    /** P8:来源 IM 会话 cid(仅忙闲页链路传);变更/取消时后端向其推卡片。 */
    @Json(name = "source_conversation_id") val sourceConversationId: String? = null,
    /**
     * P9 会议室 id。`""` = 不预订。**必须用空串而不是 null** —— Moshi 不
     * 序列化 null,发 null 等同于「字段缺省 = 不动」,清不掉已有预订;后端
     * 对 `""` 和 `null` 都按「释放」处理。全天日程不允许带(服务端 400)。
     */
    @Json(name = "meeting_room_id") val meetingRoomId: String? = null,
    /**
     * 是否随日程开一场视频会议(对标飞书「移除视频会议」)。null = 不传,
     * 服务端按缺省 = 开处理(与改动前一致)。
     */
    @Json(name = "with_video_meeting") val withVideoMeeting: Boolean? = null,
)

/**
 * PATCH body for editing an event.
 *
 * P8 编辑增删参与者:``attendee_ids`` null = 不动参与者(Moshi 缺省不序列化
 * null,后端按缺省处理);传列表 = **全量同步**(新面孔补进、不在列表的移除
 * 并同步移出 Room,组织者恒保留)。重复日程编辑不传(服务端三选路径剔除)。
 */
@JsonClass(generateAdapter = true)
data class UpdateEventRequest(
    val title: String,
    val description: String,
    @Json(name = "start_at") val startAt: String,
    @Json(name = "end_at") val endAt: String,
    @Json(name = "all_day") val allDay: Boolean,
    val reminders: List<Int>,
    @Json(name = "attendee_ids") val attendeeIds: List<String>? = null,
    @Json(name = "attendee_entries") val attendeeEntries: List<AttendeeEntryRequest>? = null,
    val visibility: String,
    /**
     * P2-M2 重复子场次的编辑范围:one|following|all。单次/主事件传 null——
     * 后端 `get('edit_scope') or ''` 会把 null 当缺省(主事件缺省=全部)。
     */
    @Json(name = "edit_scope") val editScope: String? = null,
    /**
     * P9 会议室:`""` = 释放已有预订,uuid = 预订/换房。同样不能用 null 表达
     * 「清空」—— Moshi 会把它丢掉,后端就当没提过这个字段。
     */
    @Json(name = "meeting_room_id") val meetingRoomId: String? = null,
    /**
     * 增删视频会议。**null = 不动**(Moshi 不序列化 null,服务端按字段缺省
     * 处理)—— 与创建时的「缺省 = 开」不同,否则任何一次标量编辑都会给本来
     * 没有会议的日程凭空补一个房间。重复日程的系列级编辑不传。
     */
    @Json(name = "with_video_meeting") val withVideoMeeting: Boolean? = null,
)

/**
 * 拖动改期专用的极简 PATCH body:只带起止,别的字段一概不提 —— 后端
 * partial_update 缺省即不动(标题/描述/提醒/参与者/会议室都保持原样),
 * 也就不用先 GET 一遍详情再回填。仅用于「我组织的非重复日程」。
 */
@JsonClass(generateAdapter = true)
data class RescheduleEventRequest(
    @Json(name = "start_at") val startAt: String,
    @Json(name = "end_at") val endAt: String,
)

@JsonClass(generateAdapter = true)
data class RsvpRequest(val status: String)

/**
 * rsvp 端点只回 `{"status": "..."}`,不是完整 event——按 CalendarEventDto 解析
 * 会因缺 `id` 抛 JsonDataException,把服务端已成功的回复误判成失败。
 */
@JsonClass(generateAdapter = true)
data class RsvpResponseDto(val status: String = "")
