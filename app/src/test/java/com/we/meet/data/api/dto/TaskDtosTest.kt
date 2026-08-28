package com.we.meet.data.api.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDtosTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun pagedTaskResponseMapsBackendFieldNames() {
        val json = """
            {
              "count":1,
              "next":null,
              "previous":null,
              "results":[{
                "id":"task-1",
                "title":"Ship Android tasks",
                "creator":{"id":"user-1","full_name":"Alex"},
                "assignees":[{"id":"user-1","full_name":"Alex"}],
                "status":"todo",
                "priority":"urgent",
                "task_list":{"id":"list-1","name":"Mobile","color":"blue"},
                "descendant_progress":{"completed":1,"total":3},
                "due_date":"2026-08-29",
                "can_update_status":true,
                "can_delete":true,
                "can_comment":true,
                "is_following":true
              }]
            }
        """.trimIndent()

        val page = moshi.adapter(PagedTasksDto::class.java).fromJson(json)!!
        val task = page.results.single()

        assertEquals("task-1", task.id)
        assertEquals("Alex", task.assignees.single().displayName)
        assertEquals("Mobile", task.taskList?.name)
        assertEquals(3, task.descendantProgress.total)
        assertTrue(task.canUpdateStatus)
        assertTrue(task.isFollowing)
    }
}
