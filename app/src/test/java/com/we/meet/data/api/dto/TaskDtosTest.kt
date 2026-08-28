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
                "can_manage_attachments":true,
                "can_create_subtasks":true,
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
        assertTrue(task.canManageAttachments)
        assertTrue(task.canCreateSubtasks)
        assertTrue(task.isFollowing)
    }

    @Test
    fun attachmentAndUploadPolicyResponsesMap() {
        val attachmentJson = """
            {
              "id":"attachment-1",
              "file_id":"file-1",
              "filename":"brief.pdf",
              "mimetype":"application/pdf",
              "size":4096,
              "url":"/api/v1.0/tasks/task-1/attachments/attachment-1/download/",
              "uploader":{"id":"user-1","short_name":"Alex"}
            }
        """.trimIndent()
        val uploadJson = """
            {
              "id":"file-1",
              "filename":"brief.pdf",
              "upload_state":"pending",
              "policy":"https://storage.example.test/signed"
            }
        """.trimIndent()

        val attachment = moshi.adapter(TaskAttachmentDto::class.java).fromJson(attachmentJson)!!
        val upload = moshi.adapter(FileUploadDto::class.java).fromJson(uploadJson)!!

        assertEquals("brief.pdf", attachment.filename)
        assertEquals(4096L, attachment.size)
        assertEquals("Alex", attachment.uploader?.displayName)
        assertEquals("https://storage.example.test/signed", upload.policy)
    }

    @Test
    fun taskListNavigationMapsPermissionsAndGroup() {
        val listJson = """
            {
              "id":"list-1",
              "name":"Mobile",
              "list_group":{"id":"group-1","name":"Product","sort_order":2},
              "can_create_tasks":true,
              "can_manage":true,
              "can_delete":true,
              "task_count":8
            }
        """.trimIndent()
        val groupJson = """
            {
              "id":"group-1",
              "name":"Product",
              "sort_order":2,
              "list_count":1,
              "can_manage":true
            }
        """.trimIndent()

        val list = moshi.adapter(TaskListDto::class.java).fromJson(listJson)!!
        val group = moshi.adapter(TaskListGroupDto::class.java).fromJson(groupJson)!!

        assertEquals("Product", list.listGroup?.name)
        assertEquals(8, list.taskCount)
        assertTrue(list.canManage)
        assertTrue(list.canDelete)
        assertTrue(group.canManage)
    }

    @Test
    fun createTaskListRequestUsesBackendGroupField() {
        val json = moshi.adapter(CreateTaskListRequest::class.java).toJson(
            CreateTaskListRequest(name = "Mobile", listGroupId = "group-1"),
        )

        assertTrue(json.contains("\"list_group_id\":\"group-1\""))
        assertTrue(json.contains("\"name\":\"Mobile\""))
    }

    @Test
    fun detailCollaborationResponsesMapFollowersAndActivity() {
        val taskJson = """
            {
              "id":"task-1",
              "title":"Ship Android tasks",
              "creator":{"id":"user-1","full_name":"Alex"},
              "assignees":[{"id":"user-2","full_name":"Bo"}],
              "followers":[{"id":"user-3","full_name":"Casey"}],
              "can_edit":true,
              "can_manage_followers":true
            }
        """.trimIndent()
        val activityJson = """
            {
              "id":"activity-1",
              "actor":{"id":"user-1","full_name":"Alex"},
              "event":"priority_changed",
              "changes":{"priority":{"before":"low","after":"high"}},
              "created_at":"2026-08-28T09:30:00Z"
            }
        """.trimIndent()

        val task = moshi.adapter(TaskDto::class.java).fromJson(taskJson)!!
        val activity = moshi.adapter(TaskActivityDto::class.java).fromJson(activityJson)!!

        assertEquals("Casey", task.followers.single().displayName)
        assertTrue(task.canEdit)
        assertTrue(task.canManageFollowers)
        assertEquals("priority_changed", activity.event)
        assertEquals("Alex", activity.actor?.displayName)
    }

    @Test
    fun taskPatchAndFollowerRequestsUseBackendFieldNames() {
        val patchJson = moshi.adapter(PatchTaskRequest::class.java).toJson(
            PatchTaskRequest(
                dueDate = "2026-09-01",
                priority = "high",
                assigneeIds = listOf("user-2"),
                recurrenceScope = "one",
            ),
        )
        val followerJson = moshi.adapter(AddTaskFollowersRequest::class.java).toJson(
            AddTaskFollowersRequest(listOf("user-3")),
        )

        assertTrue(patchJson.contains("\"due_date\":\"2026-09-01\""))
        assertTrue(patchJson.contains("\"assignee_ids\":[\"user-2\"]"))
        assertTrue(patchJson.contains("\"recurrence_scope\":\"one\""))
        assertTrue(followerJson.contains("\"follower_ids\":[\"user-3\"]"))
    }
}
