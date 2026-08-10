package com.we.meet.data.api

import com.we.meet.data.api.dto.MeetingRoomAvailabilityDto
import com.we.meet.data.api.dto.MeetingRoomFacilityDto
import com.we.meet.data.api.dto.MeetingRoomNodeDto
import com.we.meet.data.api.dto.MeetingRoomDto
import com.we.meet.data.api.dto.MeetingRoomTimelineDto
import com.we.meet.data.api.dto.PagedMeetingRoomsDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Meeting-room browsing endpoints (P9 会议室, core/api/meeting_rooms.py).
 *
 * Read-only: booking happens by putting a room on a calendar event
 * (`CreateEventRequest.meetingRoomId`), so cancelling the event releases the
 * room for free. Admin CRUD lives on the web console only.
 */
interface MeetingRoomApi {

    @GET("api/v1.0/meeting-rooms/{id}/")
    suspend fun getRoom(@Path("id") id: String): MeetingRoomDto

    /** The country/region through building hierarchy, flat and unpaginated. */
    @GET("api/v1.0/meeting-room-nodes/")
    suspend fun listNodes(): List<MeetingRoomNodeDto>

    /** The org's facility dictionary, for the filter chips. */
    @GET("api/v1.0/meeting-room-facilities/")
    suspend fun listFacilities(): List<MeetingRoomFacilityDto>

    @GET("api/v1.0/meeting-rooms/")
    suspend fun listRooms(
        @Query("q") q: String? = null,
        /** Node id — matches the node *and its whole subtree*. */
        @Query("node") node: String? = null,
        @Query("capacity_min") capacityMin: Int? = null,
        /** Comma-separated facility ids; AND semantics. */
        @Query("facilities") facilities: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): PagedMeetingRoomsDto

    /**
     * Rooms flagged free / busy for a window.
     *
     * `excludeEventId` drops the event being edited (and, for a series, its
     * occurrences) — otherwise rescheduling reports the room as taken by itself.
     * Windows longer than 31 days are rejected (400).
     */
    @GET("api/v1.0/meeting-rooms/availability/")
    suspend fun availability(
        /** ISO 8601 (UTC). */
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("exclude_event_id") excludeEventId: String? = null,
        @Query("node") node: String? = null,
        @Query("capacity_min") capacityMin: Int? = null,
        @Query("facilities") facilities: String? = null,
        @Query("q") q: String? = null,
        @Query("only_available") onlyAvailable: Boolean? = null,
    ): MeetingRoomAvailabilityDto

    /**
     * Occupancy per room for a window — the timeline view's data.
     *
     * Unlike [availability] this names the organizer: staring at a room's
     * schedule is how you find who to ask about that 2pm block. Titles of
     * private events come back null. Windows longer than 7 days are rejected.
     */
    @GET("api/v1.0/meeting-rooms/timeline/")
    suspend fun timeline(
        /** ISO 8601 (UTC). */
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("node") node: String? = null,
        @Query("capacity_min") capacityMin: Int? = null,
        @Query("facilities") facilities: String? = null,
        @Query("q") q: String? = null,
    ): MeetingRoomTimelineDto
}
