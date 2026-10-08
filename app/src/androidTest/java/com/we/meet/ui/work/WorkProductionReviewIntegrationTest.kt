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

/** Read-only production receipt check; never creates a task or a paid review. */
class WorkProductionReviewIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneReadsRealPiReviewOfDesktopResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("workProductionReview") == "1")
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".fixturecohort"))
        require(BuildConfig.WE_MEET_BASE_URL == "https://meet.we-meet.online")
        val broker = java.net.URL("http://127.0.0.1:48763/session").openConnection() as java.net.HttpURLConnection
        broker.connectTimeout = 10000
        broker.readTimeout = 10000
        val session = try {
            broker.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            broker.disconnect()
        }
        val tokens = TokenStore(context)
        tokens.accessToken = session.getString("access")
        val store = ViewModelStore()
        lateinit var vm: WorkViewModel
        compose.runOnUiThread {
            vm = WorkViewModel(WorkRepository(ApiClient(tokens).workApi), SavedStateHandle())
            store.put("work", vm)
        }
        try {
            compose.setContent { WeMeetTheme { WorkScreen(vm) {} } }
            compose.waitUntil(20000) { !vm.ui.value.loading }
            assertEquals("", vm.ui.value.error)
            compose.runOnUiThread { vm.select(session.getString("task_id")) }
            compose.waitUntil(20000) { !vm.ui.value.acting }
            assertEquals("", vm.ui.value.error)
            val run = vm.ui.value.selected!!.runs.single()
            assertEquals("succeeded", run.status)
            assertEquals(session.getString("source_run_id"), run.id)
            val review = vm.ui.value.reviews.single { it.id == session.getString("review_id") }
            assertEquals(session.getString("review_id"), review.id)
            assertEquals(run.id, review.sourceRunId)
            assertEquals("succeeded", review.status)
            assertEquals("qwen3.8-flash", review.model)
            assertEquals(listOf("report.md"), review.selection.map { it.name })
            assertTrue(review.inputTokens != null && review.outputTokens != null)
            assertTrue(review.inputTokens!! + review.outputTokens!! <= 20000)
            assertTrue(review.report.verdict in setOf("no_issues", "needs_changes", "inconclusive"))
            compose.onNodeWithText(context.getString(R.string.work_review_succeeded)).performScrollTo().assertIsDisplayed()
            if (review.report.missingInformation.isNotEmpty()) {
                compose.onNodeWithText(context.getString(R.string.work_review_inconclusive)).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText(review.report.summary, substring = false).performScrollTo().assertIsDisplayed()
            val folder = File(context.getExternalFilesDir(null), "work-production-review").apply { mkdirs() }
            File(folder, "review.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText(context.getString(R.string.work_view_file, "report.md")).performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.ui.value.acting && vm.ui.value.preview.isNotEmpty() }
            assertEquals("cross-device-marker-20261007\n", vm.ui.value.preview)
            File(folder, "receipt.json").writeText(JSONObject().apply {
                put("passed", true)
                put("source_run_id", run.id)
                put("review_id", review.id)
                put("model", review.model)
                put("input_tokens", review.inputTokens)
                put("output_tokens", review.outputTokens)
                put("review_visible", true)
                put("uncertainty_visible", review.report.missingInformation.isNotEmpty())
                put("original_result_preview_verified", true)
                put("new_tasks_created", 0)
                put("new_reviews_created", 0)
            }.toString(2))
        } finally {
            compose.runOnUiThread { store.clear() }
            tokens.clear()
        }
    }
}
