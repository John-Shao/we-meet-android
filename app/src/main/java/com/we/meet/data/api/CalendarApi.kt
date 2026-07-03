package com.we.meet.data.api

import com.we.meet.data.api.dto.CalendarEventDto
import com.we.meet.data.api.dto.CreateEventRequest
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
    ): PagedCalendarEventsDto

    @GET("api/v1.0/calendar-events/{id}/")
    suspend fun getEvent(@Path("id") id: String): CalendarEventDto

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

    @DELETE("api/v1.0/calendar-events/{id}/")
    suspend fun deleteEvent(@Path("id") id: String)
}
