package com.we.meet.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.we.meet.data.api.*
import com.we.meet.data.repository.WorkRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

private class WorkFixture : WorkApi {
    val requests = mutableListOf<RemoteWorkRequest>()
    var lose = true
    var body = "# approved desktop result\n"
    override suspend fun capabilities() = WorkCapabilities(true)
    override suspend fun workspaces() = DesktopWorkspaces("work-device/v1", listOf(DesktopWorkspace("folder", "device", "Computer", "Project", "deepseek-flash", true, false)))
    override suspend fun dispatch(request: RemoteWorkRequest): RemoteWorkResponse {
        requests += request
        if (lose) { lose = false; throw IOException("Lost admission ACK") }
        val run = WorkRunDto(request.runId, "queued", "local")
        return RemoteWorkResponse("work-device/v1", WorkTaskDto("task", request.goal, listOf(run)), run)
    }
    override suspend fun tasks(page: Int) = WorkTasksPage(emptyList())
    override suspend fun task(id: String) = WorkTaskDto(id, "goal", emptyList())
    override suspend fun cancel(id: String, empty: Map<String, String>) = WorkRunDto(id, "canceled", "local")
    override suspend fun files(id: String) = emptyList<WorkFileDto>()
    override suspend fun download(id: String, name: String): ResponseBody = body.toResponseBody()
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkTest {
    @Test fun lostResponseAndRecreationReuseOriginalRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stores = mutableListOf<ViewModelStore>()
        try {
            val api = WorkFixture()
            val saved = SavedStateHandle()
            fun vm(): WorkViewModel {
                val vm = WorkViewModel(WorkRepository(api), saved)
                stores += ViewModelStore().also { it.put("work", vm) }
                return vm
            }
            val first = vm(); runCurrent()
            first.dispatch("folder", "Original goal"); runCurrent()
            assertTrue(first.ui.value.uncertain)
            stores[0].clear()
            val recreated = vm(); runCurrent()
            recreated.dispatch("different-folder", "changed goal"); runCurrent()
            assertEquals(2, api.requests.size)
            assertEquals(api.requests[0], api.requests[1])
            assertEquals("Original goal", api.requests[1].goal)
            assertFalse(recreated.ui.value.uncertain)
            assertEquals("task", recreated.ui.value.selected?.id)
        } finally { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    }

    @Test fun resultPreviewVerifiesHashAndByteLimit() = runTest {
        val api = WorkFixture(); val repo = WorkRepository(api)
        val sha = MessageDigest.getInstance("SHA-256").digest(api.body.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(api.body, repo.preview("run", WorkFileDto("report.md", sha)))
        api.body = "changed"
        try { repo.preview("run", WorkFileDto("report.md", sha)); fail("changed result accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("work_file_changed", expected.message) }
        api.body = "x".repeat(400001)
        try { repo.preview("run", WorkFileDto("report.md", sha)); fail("oversized result accepted") }
        catch (expected: IllegalArgumentException) { assertEquals("work_file_too_large", expected.message) }
    }

    @Test fun wireNamesAndUnknownDeviceStateRemainExplicit() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val request = RemoteWorkRequest("run", "workspace", "goal")
        val json = moshi.adapter(RemoteWorkRequest::class.java).toJson(request)
        assertTrue(json.contains("\"run_id\":\"run\""))
        assertTrue(json.contains("\"workspace_id\":\"workspace\""))
        val run = moshi.adapter(WorkRunDto::class.java).fromJson("""{"id":"run","status":"disconnected","execution_target":"local","remote_requested":true} """)!!
        assertTrue(run.remoteRequested)
        assertEquals(com.we.meet.R.string.work_status_disconnected, workStatus(run.status, true))
        assertEquals(com.we.meet.R.string.work_status_unknown, workStatus("new-upstream-state", true))
    }
}
