package com.we.meet.data.api

import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.CreateEventRequest
import com.we.meet.data.api.dto.FreeBusyResponseDto
import com.we.meet.data.api.dto.PagedCalendarEventsDto
import com.we.meet.data.api.dto.RsvpRequest
import com.we.meet.data.api.dto.UpdateEventRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Calendar / scheduling endpoints (core/api/calendar.py). List returns events
 * the caller organizes OR attends; creating one auto-provisions a Room with
 * `scheduled_at = start_at` and grants every attendee access.
 */
interface CalendarApi {

    @GET("api/v1.0/calendar-events/")
    suspend fun listEvents(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        /** ISO 8601 window (overlap filter, list-only) — narrows to the visible months. */
        @Query("start") start: String? = null,
        @Query("end") end: String? = null,
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
    ): FreeBusyResponseDto

    @POST("api/v1.0/calendar-events/")
    suspend fun createEvent(@Body body: CreateEventRequest): CalendarEventDto

    @POST("api/v1.0/calendar-events/{id}/rsvp/")
    suspend fun rsvp(@Path("id") id: String, @Body body: RsvpRequest): CalendarEventDto

    /**
     * Update scalar fields (title/description/time/reminders). Backend does NOT
     * re-sync attendees on update, so the edit UI hides the attendee picker.
     */
    @PATCH("api/v1.0/calendar-events/{id}/")
    suspend fun updateEvent(@Path("id") id: String, @Body body: UpdateEventRequest): CalendarEventDto

    /**
     * Delete an event. [scope] = "following"(仅重复子场次)截断该场次及之后;
     * null = 缺省语义(子场次=仅此次记 exdate;主事件=删整个系列)。
     */
    @DELETE("api/v1.0/calendar-events/{id}/")
    suspend fun deleteEvent(
        @Path("id") id: String,
        @Query("scope") scope: String? = null,
    )
}
