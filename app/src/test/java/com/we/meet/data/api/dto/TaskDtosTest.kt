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
              "is_archived":true,
              "can_create_tasks":true,
              "can_manage":true,
              "can_archive":true,
              "can_delete":true,
              "task_count":8,
              "groups":[{
                "id":"task-group-1",
                "name":"In progress",
                "sort_order":1,
                "task_count":3,
                "can_delete":false
              }]
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
        assertTrue(list.isArchived)
        assertTrue(list.canManage)
        assertTrue(list.canArchive)
        assertTrue(list.canDelete)
        assertEquals("In progress", list.groups.single().name)
        assertEquals(3, list.groups.single().taskCount)
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
    fun patchTaskListRequestUsesBackendArchiveField() {
        val json = moshi.adapter(PatchTaskListRequest::class.java).toJson(
            PatchTaskListRequest(isArchived = true),
        )

        assertTrue(json.contains("\"is_archived\":true"))
    }

    @Test
    fun taskGroupMapsPermissionsAndRequestsUseBackendSortField() {
        val groupJson = """
            {
              "id":"task-group-1",
              "name":"In progress",
              "sort_order":2,
              "task_count":0,
              "can_delete":true
            }
        """.trimIndent()

        val group = moshi.adapter(TaskGroupDto::class.java).fromJson(groupJson)!!
        val createJson = moshi.adapter(CreateTaskGroupRequest::class.java).toJson(
            CreateTaskGroupRequest(name = "In progress", sortOrder = 2),
        )
        val patchJson = moshi.adapter(PatchTaskGroupRequest::class.java).toJson(
            PatchTaskGroupRequest(name = "Doing", sortOrder = 1),
        )

        assertEquals("task-group-1", group.id)
        assertEquals(2, group.sortOrder)
        assertEquals(0, group.taskCount)
        assertTrue(group.canDelete)
        assertTrue(createJson.contains("\"sort_order\":2"))
        assertTrue(patchJson.contains("\"name\":\"Doing\""))
        assertTrue(patchJson.contains("\"sort_order\":1"))
    }

    @Test
    fun taskStatisticsMapsNavigationCounts() {
        val json = """
            {
              "hierarchy_scope":"include_descendants",
              "summary":{
                "total":12,
                "open":8,
                "completed":4,
                "overdue":2,
                "completion_rate":33
              },
              "workload":[],
              "groups":[]
            }
        """.trimIndent()

        val statistics = moshi.adapter(TaskStatisticsDto::class.java).fromJson(json)!!

        assertEquals(12, statistics.summary.total)
        assertEquals(8, statistics.summary.openCount)
        assertEquals(4, statistics.summary.completed)
        assertEquals(2, statistics.summary.overdue)
        assertEquals(33, statistics.summary.completionRate)
    }

    @Test
    fun standaloneTaskCountMapsNavigationResponse() {
        val count = moshi.adapter(StandaloneTaskCountDto::class.java)
            .fromJson("{\"count\":7}")!!

        assertEquals(7, count.count)
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

    @Test
    fun subtaskOrderRequestUsesExactBackendSnapshotField() {
        val json = moshi.adapter(ReorderTaskSubtasksRequest::class.java).toJson(
            ReorderTaskSubtasksRequest(listOf("task-2", "task-1")),
        )

        assertEquals("{\"task_ids\":[\"task-2\",\"task-1\"]}", json)
    }

    @Test
    fun taskRecurrenceMapsAndRequestUsesBackendFields() {
        val taskJson = """
            {
              "id":"task-1",
              "title":"Weekly review",
              "creator":{"id":"user-1","full_name":"Alex"},
              "recurrence":{
                "rule_id":"rule-1",
                "frequency":"weekly",
                "interval":2,
                "timezone":"Asia/Shanghai",
                "end_date":"2026-12-31",
                "max_occurrences":null,
                "generated_count":3,
                "next_occurrence_date":"2026-09-11",
                "is_active":true,
                "last_error":"",
                "sequence":2,
                "can_manage":true
              }
            }
        """.trimIndent()
        val requestJson = moshi.adapter(TaskRecurrenceRequest::class.java).toJson(
            TaskRecurrenceRequest(
                frequency = "monthly",
                interval = 2,
                endDate = "2026-12-31",
            ),
        )

        val recurrence = moshi.adapter(TaskDto::class.java).fromJson(taskJson)!!.recurrence!!
        assertEquals("weekly", recurrence.frequency)
        assertEquals(2, recurrence.interval)
        assertEquals("2026-09-11", recurrence.nextOccurrenceDate)
        assertTrue(recurrence.canManage)
        assertTrue(requestJson.contains("\"frequency\":\"monthly\""))
        assertTrue(requestJson.contains("\"end_date\":\"2026-12-31\""))
        assertTrue(requestJson.contains("\"max_occurrences\":null").not())
    }

    @Test
    fun taskHierarchyResponsesMapParentPathCandidatesAndImpact() {
        val taskJson = """
            {
              "id":"task-2",
              "title":"Android implementation",
              "creator":{"id":"user-1","full_name":"Alex"},
              "parent_id":"task-1",
              "ancestor_path":[
                {"id":"task-1","title":"Mobile launch","depth":0},
                {"id":"task-2","title":"Android implementation","depth":1}
              ]
            }
        """.trimIndent()
        val candidatesJson = """
            [{
              "id":"task-3",
              "title":"Release preparation",
              "depth":1,
              "ancestor_path":[
                {"id":"task-4","title":"Product","depth":0},
                {"id":"task-3","title":"Release preparation","depth":1}
              ]
            }]
        """.trimIndent()
        val impactJson = """
            {"task_id":"task-2","node_count":3,"descendant_count":2,"maximum_depth":3}
        """.trimIndent()

        val task = moshi.adapter(TaskDto::class.java).fromJson(taskJson)!!
        val candidateType = com.squareup.moshi.Types.newParameterizedType(
            List::class.java,
            TaskParentCandidateDto::class.java,
        )
        val candidates = moshi.adapter<List<TaskParentCandidateDto>>(candidateType)
            .fromJson(candidatesJson)!!
        val impact = moshi.adapter(TaskSubtreeImpactDto::class.java).fromJson(impactJson)!!

        assertEquals("task-1", task.parentId)
        assertEquals("Mobile launch", task.ancestorPath.first().title)
        assertEquals(1, candidates.single().depth)
        assertEquals("Product", candidates.single().ancestorPath.first().title)
        assertEquals(3, impact.nodeCount)
        assertEquals(2, impact.descendantCount)
        assertEquals(3, impact.maximumDepth)
    }
}
