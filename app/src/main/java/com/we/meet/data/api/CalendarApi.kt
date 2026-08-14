package com.we.meet.data.api

import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.CalendarPreferenceDto
import com.we.meet.data.api.dto.CalendarAccessGrantDto
import com.we.meet.data.api.dto.CalendarSubscriptionDto
import com.we.meet.data.api.dto.CreateEventRequest
import com.we.meet.data.api.dto.FreeBusyResponseDto
import com.we.meet.data.api.dto.PagedCalendarEventsDto
import com.we.meet.data.api.dto.PersonalCalendarDto
import com.we.meet.data.api.dto.RescheduleEventRequest
import com.we.meet.data.api.dto.RsvpRequest
import com.we.meet.data.api.dto.RsvpResponseDto
import com.we.meet.data.api.dto.UpdateEventRequest
import com.we.meet.data.api.dto.SaveCalendarGrantRequest
import com.we.meet.data.api.dto.SubscribeCalendarRequest
import com.we.meet.data.api.dto.UpdateCalendarGrantRequest
import com.we.meet.data.api.dto.UpdatePersonalCalendarRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PUT
import okhttp3.RequestBody

/**
 * Calendar / scheduling endpoints (core/api/calendar.py). List returns events
 * the caller organizes OR attends; creating one auto-provisions a Room with
 * `scheduled_at = start_at` and grants every attendee access.
 */
interface CalendarApi {

    @GET("api/v1.0/calendars/")
    suspend fun listCalendars(): List<com.we.meet.data.api.dto.UnifiedCalendarDto>

    @GET("api/v1.0/calendars/{id}/")
    suspend fun getCalendar(
        @Path("id") id: String,
    ): com.we.meet.data.api.dto.UnifiedCalendarDto

    @POST("api/v1.0/calendars/")
    suspend fun createCalendar(@Body body: com.we.meet.data.api.dto.CreateCalendarRequest): com.we.meet.data.api.dto.UnifiedCalendarDto

    @GET("api/v1.0/calendars/discover/")
    suspend fun discoverCalendars(
        @Query("type") type: String,
        @Query("q") query: String = "",
    ): List<com.we.meet.data.api.dto.UnifiedCalendarDto>

    @PATCH("api/v1.0/calendars/{id}/")
    suspend fun updateCalendar(
        @Path("id") id: String,
        @Body body: com.we.meet.data.api.dto.UpdateCalendarRequest,
    ): com.we.meet.data.api.dto.UnifiedCalendarDto

    @DELETE("api/v1.0/calendars/{id}/")
    suspend fun deleteCalendar(@Path("id") id: String)

    @POST("api/v1.0/calendars/{id}/restore/")
    suspend fun restoreCalendar(@Path("id") id: String): com.we.meet.data.api.dto.UnifiedCalendarDto

    @PUT("api/v1.0/calendars/{id}/subscription/")
    suspend fun updateCalendarSubscription(
        @Path("id") id: String,
        @Body body: com.we.meet.data.api.dto.CalendarSubscriptionRequest,
    ): com.we.meet.data.api.dto.UnifiedCalendarDto

    @DELETE("api/v1.0/calendars/{id}/subscription/")
    suspend fun deleteCalendarSubscription(@Path("id") id: String)

    @GET("api/v1.0/calendars/{id}/members/")
    suspend fun listCalendarMembers(@Path("id") id: String): List<com.we.meet.data.api.dto.CalendarMemberDto>

    @POST("api/v1.0/calendars/{id}/members/")
    suspend fun addCalendarMember(
        @Path("id") id: String,
        @Body body: com.we.meet.data.api.dto.CalendarMemberRequest,
    ): com.we.meet.data.api.dto.CalendarMemberDto

    @PATCH("api/v1.0/calendars/{id}/members/{memberId}/")
    suspend fun updateCalendarMember(
        @Path("id") id: String,
        @Path("memberId") memberId: String,
        @Body body: Map<String, String>,
    ): com.we.meet.data.api.dto.CalendarMemberDto

    @DELETE("api/v1.0/calendars/{id}/members/{memberId}/")
    suspend fun deleteCalendarMember(@Path("id") id: String, @Path("memberId") memberId: String)

    @GET("api/v1.0/calendars/{id}/share-link/")
    suspend fun getCalendarShareLink(@Path("id") id: String): com.we.meet.data.api.dto.CalendarShareLinkDto

    @POST("api/v1.0/calendars/{id}/share-link/")
    suspend fun resetCalendarShareLink(@Path("id") id: String): com.we.meet.data.api.dto.CalendarShareLinkDto

    @GET("api/v1.0/calendar-share/{token}/")
    suspend fun previewCalendarShare(@Path(value = "token", encoded = true) token: String): com.we.meet.data.api.dto.UnifiedCalendarDto

    @POST("api/v1.0/calendar-share/{token}/")
    suspend fun subscribeCalendarShare(@Path(value = "token", encoded = true) token: String): com.we.meet.data.api.dto.UnifiedCalendarDto

    @POST("api/v1.0/calendars/{id}/exports/")
    suspend fun createCalendarExport(
        @Path("id") id: String,
        @Body body: com.we.meet.data.api.dto.CalendarExportRequest,
    ): com.we.meet.data.api.dto.CalendarExportJobDto

    @GET("api/v1.0/calendar-preferences/me/")
    suspend fun getCalendarPreference(): CalendarPreferenceDto

    /** RequestBody is intentional: it preserves JSON null when clearing the default reminder. */
    @PATCH("api/v1.0/calendar-preferences/me/")
    suspend fun updateCalendarPreference(@Body body: RequestBody): CalendarPreferenceDto

    @GET("api/v1.0/personal-calendars/mine/")
    suspend fun getMyCalendar(): PersonalCalendarDto

    @PATCH("api/v1.0/personal-calendars/{id}/")
    suspend fun updatePersonalCalendar(
        @Path("id") id: String,
        @Body body: UpdatePersonalCalendarRequest,
    ): PersonalCalendarDto

    @GET("api/v1.0/personal-calendars/{id}/events/")
    suspend fun listPersonalCalendarEvents(
        @Path("id") id: String,
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("date_start") dateStart: String? = null,
        @Query("date_end") dateEnd: String? = null,
    ): List<CalendarEventDto>

    @GET("api/v1.0/calendar-access-grants/")
    suspend fun listCalendarGrants(): List<CalendarAccessGrantDto>

    @POST("api/v1.0/calendar-access-grants/")
    suspend fun saveCalendarGrant(
        @Body body: SaveCalendarGrantRequest,
    ): CalendarAccessGrantDto

    @PATCH("api/v1.0/calendar-access-grants/{id}/")
    suspend fun updateCalendarGrant(
        @Path("id") id: String,
        @Body body: UpdateCalendarGrantRequest,
    ): CalendarAccessGrantDto

    @DELETE("api/v1.0/calendar-access-grants/{id}/")
    suspend fun deleteCalendarGrant(@Path("id") id: String)

    @GET("api/v1.0/calendar-subscriptions/")
    suspend fun listCalendarSubscriptions(): List<CalendarSubscriptionDto>

    @POST("api/v1.0/calendar-subscriptions/")
    suspend fun subscribeCalendar(
        @Body body: SubscribeCalendarRequest,
    ): CalendarSubscriptionDto

    @DELETE("api/v1.0/calendar-subscriptions/{id}/")
    suspend fun unsubscribeCalendar(@Path("id") id: String)

    @GET("api/v1.0/calendar-events/")
    suspend fun listEvents(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        /** ISO 8601 window (overlap filter, list-only) — narrows to the visible months. */
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
        @Query("date_start") dateStart: String? = null,
        @Query("date_end") dateEnd: String? = null,
    ): PagedCalendarEventsDto

    @GET("api/v1.0/calendar-events/{id}/")
    suspend fun getEvent(@Path("id") id: String): CalendarEventDto

    /**
     * P8 忙闲(P2-M3 端点):仅返回 busy 区间不泄露标题;窗口 ≤31 天;
     * 跨组织/非法 id 被服务端静默丢弃 —— 该 user_id 直接缺席于 results,
     * UI 必须把缺席列显式置灰(「日历不可见」),不得静默少列。
     */
    @GET("api/v1.0/calendar-events/freebusy/")
    suspend fun freeBusy(
        /** 逗号分隔的 we-meet user UUID。 */
        @Query("attendee_ids") attendeeIds: String,
        /** ISO 8601 (UTC)。 */
        @Query("start") start: String,
        @Query("end") end: String,
        /** 编辑态传当前日程 id:把它自己从忙闲里剔除,原参与者不被这场误报。 */
        @Query("exclude_event_id") excludeEventId: String? = null,
    ): FreeBusyResponseDto

    @POST("api/v1.0/calendar-events/")
    suspend fun createEvent(@Body body: CreateEventRequest): CalendarEventDto

    /** 后端只回 `{"status": ...}`(calendar.py rsvp action),成功后靠 getEvent 刷新。 */
    @POST("api/v1.0/calendar-events/{id}/rsvp/")
    suspend fun rsvp(@Path("id") id: String, @Body body: RsvpRequest): RsvpResponseDto

    /**
     * Update scalar fields (title/description/time/reminders). Backend does NOT
     * re-sync attendees on update, so the edit UI hides the attendee picker.
     */
    @PATCH("api/v1.0/calendar-events/{id}/")
    suspend fun updateEvent(@Path("id") id: String, @Body body: UpdateEventRequest): CalendarEventDto

    /**
     * 日/三日视图长按拖动改期:只 PATCH 起止(其余字段缺省 = 不动)。后端照常
     * 同步 Room.scheduled_at、重订会议室(冲突 409)、并向来源会话推
     * time_changed 卡片。仅组织者可调,重复日程走编辑页三选,不走这里。
     */
    @PATCH("api/v1.0/calendar-events/{id}/")
    suspend fun rescheduleEvent(
        @Path("id") id: String,
        @Body body: RescheduleEventRequest,
    ): CalendarEventDto

    /**
     * Delete an event. Repeating events accept [scope] = one/following/all;
     * null keeps the server's event-type-specific default.
     */
    @DELETE("api/v1.0/calendar-events/{id}/")
    suspend fun deleteEvent(
        @Path("id") id: String,
        @Query("scope") scope: String? = null,
    )
}
