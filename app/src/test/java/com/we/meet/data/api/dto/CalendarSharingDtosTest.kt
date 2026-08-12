package com.we.meet.data.api.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSharingDtosTest {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Test
    fun `event update marks visibility as an explicit user choice`() {
        val request = UpdateEventRequest(
            title = "Roadmap",
            description = "",
            startAt = "2026-08-13T01:00:00Z",
            endAt = "2026-08-13T02:00:00Z",
            allDay = false,
            reminders = emptyList(),
            visibility = "public",
        )

        val json = moshi.adapter(UpdateEventRequest::class.java).toJson(request)

        assertTrue(json.contains("\"visibility\":\"public\""))
        assertTrue(json.contains("\"visibility_explicit\":true"))
    }

    @Test
    fun `subscription keeps calendar identity and effective permission`() {
        val json = """
            {
              "id":"sub-1",
              "calendar_id":"calendar-1",
              "owner":{"id":"owner-1","full_name":"Alice"},
              "permission":"free_busy",
              "enabled":true,
              "color":"#2563eb"
            }
        """.trimIndent()

        val dto = moshi.adapter(CalendarSubscriptionDto::class.java).fromJson(json)!!

        assertEquals("calendar-1", dto.calendarId)
        assertEquals("Alice", dto.owner.fullName)
        assertEquals("free_busy", dto.permission)
    }
}
