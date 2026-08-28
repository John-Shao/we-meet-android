package com.we.meet.data.repository

import com.we.meet.data.api.dto.PagedTasksDto
import com.we.meet.data.api.dto.TaskDto
import com.we.meet.data.api.dto.TaskUserDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPaginationTest {
    @Test
    fun collectTaskPagesFollowsNextAndDeduplicatesTasks() = runBlocking {
        val requestedPages = mutableListOf<Int>()

        val tasks = collectTaskPages(pageSize = 2, maxResults = 10) { page, pageSize ->
            requestedPages += page
            assertEquals(2, pageSize)
            when (page) {
                1 -> page("task-1", "task-2", next = "page-2")
                2 -> page("task-2", "task-3")
                else -> error("Unexpected page $page")
            }
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf("task-1", "task-2", "task-3"), tasks.map(TaskDto::id))
    }

    @Test
    fun collectTaskPagesStopsAtResultLimit() = runBlocking {
        val requestedPages = mutableListOf<Int>()

        val tasks = collectTaskPages(pageSize = 2, maxResults = 3) { page, _ ->
            requestedPages += page
            when (page) {
                1 -> page("task-1", "task-2", next = "page-2")
                2 -> page("task-3", "task-4", next = "page-3")
                else -> error("Pagination exceeded its bound")
            }
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf("task-1", "task-2", "task-3"), tasks.map(TaskDto::id))
    }

    private fun page(vararg ids: String, next: String? = null) = PagedTasksDto(
        count = ids.size,
        next = next,
        results = ids.map(::task),
    )

    private fun task(id: String) = TaskDto(
        id = id,
        title = id,
        creator = TaskUserDto(id = "creator"),
    )
}
