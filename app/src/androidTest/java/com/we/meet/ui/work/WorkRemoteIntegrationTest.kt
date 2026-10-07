package com.we.meet.ui.work

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.data.api.ApiClient
import com.we.meet.data.auth.TokenStore
import com.we.meet.data.repository.WorkRepository
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Opt-in loopback test: real Retrofit/Work API and an explicitly reviewed desktop. */
class WorkRemoteIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneDispatchDesktopExecutionAndPhonePreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("workCrossDevice") == "1")
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".fixturework"))
        require(BuildConfig.WE_MEET_BASE_URL == "http://127.0.0.1:48761")
        val tokens = TokenStore(context)
        tokens.accessToken = "isolated-test-account"
        val api = ApiClient(tokens).workApi
        val store = ViewModelStore()
        lateinit var vm: WorkViewModel
        compose.runOnUiThread {
            vm = WorkViewModel(WorkRepository(api), SavedStateHandle())
            store.put("work", vm)
        }
        try {
            compose.setContent { WeMeetTheme { WorkScreen(vm) {} } }
            compose.waitUntil(20000) { !vm.ui.value.loading && vm.ui.value.workspaces.isNotEmpty() }
            assertTrue(vm.ui.value.enabled)
            val workspace = vm.ui.value.workspaces.single()
            assertEquals("Cross-device workspace", workspace.label)
            val goal = "Read input.txt in the current local workspace. Write report.md containing its exact contents. Do not modify input.txt."
            compose.onNode(hasSetTextAction()).performTextInput(goal)
            compose.onAllNodes(isSelectable())[0].performClick()
            compose.onNodeWithText(context.getString(R.string.work_dispatch)).performScrollTo().performClick()
            compose.waitUntil(20000) { !vm.ui.value.acting && vm.ui.value.selected != null }
            val task = vm.ui.value.selected!!
            val run = task.runs.single()
            assertEquals("queued", run.status)
            assertEquals(workspace.label, run.workspaceLabel)
            assertTrue(run.remoteRequested)

            // Only the desktop can take this request. The test never approves a tool.
            val deadline = System.currentTimeMillis() + 600000
            while (System.currentTimeMillis() < deadline) {
                compose.runOnUiThread { vm.refresh() }
                compose.waitUntil(15000) { !vm.ui.value.loading }
                val state = vm.ui.value.selected?.runs?.lastOrNull()?.status
                if (state in setOf("failed", "canceled")) fail("Desktop task ended: $state")
                if (state == "succeeded" && vm.ui.value.files.isNotEmpty()) break
                Thread.sleep(1000)
            }
            assertEquals("succeeded", vm.ui.value.selected!!.runs.single().status)
            assertEquals(listOf("report.md"), vm.ui.value.files.map { it.name })
            compose.onNodeWithText(context.getString(R.string.work_view_file, "report.md")).performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.ui.value.acting && vm.ui.value.preview.isNotEmpty() }
            assertEquals("cross-device-marker-20261007\n", vm.ui.value.preview)
            compose.onNodeWithText(vm.ui.value.preview).performScrollTo().assertIsDisplayed()
            val directory = File(context.getExternalFilesDir(null), "work-cross-device").apply { mkdirs() }
            File(directory, "result.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText(context.getString(R.string.work_back_list)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performTextInput("Cancel this queued request without execution")
            compose.onNodeWithText(context.getString(R.string.work_dispatch)).performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.ui.value.acting && vm.ui.value.selected?.id != task.id && vm.ui.value.selected != null }
            val canceledRun = vm.ui.value.selected!!.runs.single().id
            compose.onNodeWithText(context.getString(R.string.work_cancel)).performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.ui.value.acting && vm.ui.value.selected!!.runs.single().status == "canceled" }
            File(directory, "receipt.json").writeText(JSONObject().apply {
                put("passed", true); put("task_id", task.id); put("run_id", run.id)
                put("workspace_id", workspace.id); put("file", "report.md")
                put("preview_verified", true); put("auth", "isolated-test-account; not real login")
                put("canceled_run_id", canceledRun)
            }.toString(2))
        } finally {
            compose.runOnUiThread { store.clear() }
            tokens.clear()
        }
    }
}
