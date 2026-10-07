package com.we.meet.ui.work

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.*
import com.we.meet.data.repository.WorkRepository
import com.we.meet.ui.theme.WeMeetTheme
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class WorkScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineWorkspaceDispatchAndSyncedResultPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "# Desktop fixture result\nwork-marker-6107\n"
        val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        var request: RemoteWorkRequest? = null
        val api = object : WorkApi {
            override suspend fun capabilities() = WorkCapabilities(true)
            override suspend fun workspaces() = DesktopWorkspaces("work-device/v1", listOf(DesktopWorkspace("fixture-folder", "fixture-device", "Fixture Computer", "Fixture Project", "deepseek-flash", true, false)))
            override suspend fun tasks(page: Int) = WorkTasksPage(emptyList())
            override suspend fun dispatch(body: RemoteWorkRequest): RemoteWorkResponse {
                request = body
                val run = WorkRunDto(body.runId, "succeeded", "local", "Fixture Project", true, syncedFiles = listOf("report.md"))
                return RemoteWorkResponse("work-device/v1", WorkTaskDto("fixture-task", body.goal, listOf(run)), run)
            }
            override suspend fun task(id: String) = WorkTaskDto(id, "Read input and prepare report", listOf(WorkRunDto(request!!.runId, "succeeded", "local", "Fixture Project", true, syncedFiles = listOf("report.md"))))
            override suspend fun files(id: String) = listOf(WorkFileDto("report.md", sha))
            override suspend fun download(id: String, name: String): ResponseBody = text.toResponseBody()
            override suspend fun cancel(id: String, empty: Map<String, String>) = WorkRunDto(id, "canceled", "local")
        }
        lateinit var vm: WorkViewModel
        val store = ViewModelStore()
        compose.runOnUiThread { vm = WorkViewModel(WorkRepository(api), SavedStateHandle()); store.put("work", vm) }
        try {
            compose.setContent { WeMeetTheme { WorkScreen(vm) {} } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Fixture Project").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).performTextInput("Read input and prepare report")
            compose.onAllNodes(isSelectable())[0].performClick()
            compose.onNodeWithText(context.getString(R.string.work_dispatch)).performScrollTo().performClick()
            compose.waitUntil(10000) { request != null && !vm.ui.value.acting }
            assertEquals("fixture-folder", request!!.workspaceId)
            compose.runOnUiThread { vm.select("fixture-task") }
            compose.waitUntil(10000) { vm.ui.value.files.isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.work_view_file, "report.md")).performScrollTo().performClick()
            compose.waitUntil(10000) { vm.ui.value.preview == text }
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val folder = File(context.getExternalFilesDir(null), "work-fixture").apply { mkdirs() }
            File(folder, "work-result.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText(context.getString(R.string.work_back_list)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.work_dispatch_title)).assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
