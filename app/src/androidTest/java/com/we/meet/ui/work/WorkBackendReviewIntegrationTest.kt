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

/** Requires the opt-in real Django/PostgreSQL/Pi fixture in the backend repository. */
class WorkBackendReviewIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneReadsSyncedResultAndRealPiReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("workReviewCrossDevice") == "1")
        val taskId = requireNotNull(arguments.getString("workReviewTask"))
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".fixturework"))
        require(BuildConfig.WE_MEET_BASE_URL == "http://127.0.0.1:48761")
        val tokens = TokenStore(context)
        tokens.accessToken = "isolated-test-account"
        val repository = WorkRepository(ApiClient(tokens).workApi) {
            if (tokens.isLoggedIn()) tokens.authSnapshot().session else null
        }
        lateinit var vm: WorkViewModel
        val store = ViewModelStore()
        compose.runOnUiThread { vm = WorkViewModel(repository, SavedStateHandle()); store.put("work", vm) }
        try {
            compose.setContent { WeMeetTheme { WorkScreen(vm) {} } }
            compose.waitUntil(20000) { !vm.ui.value.loading }
            assertEquals("", vm.ui.value.error)
            compose.runOnUiThread { vm.select(taskId) }
            compose.waitUntil(20000) { !vm.ui.value.acting }
            assertEquals("", vm.ui.value.error)
            val review = vm.ui.value.reviews.single()
            assertEquals("succeeded", review.status)
            assertEquals("qwen3.8-flash", review.model)
            assertEquals(100L, review.inputTokens)
            assertEquals(30L, review.outputTokens)
            assertEquals("needs_changes", review.report.verdict)
            compose.onNodeWithText(context.getString(R.string.work_review_succeeded)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic review of the selected local result").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Shared local result").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.work_review_usage, 100, 30)).performScrollTo().assertIsDisplayed()
            val folder = File(context.getExternalFilesDir(null), "work-review-live").apply { mkdirs() }
            File(folder, "review.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText(context.getString(R.string.work_view_file, "report.md")).performScrollTo().performClick()
            compose.waitUntil(15000) { !vm.ui.value.acting }
            assertEquals("# Shared local result\n", vm.ui.value.preview)
            compose.onNodeWithText(vm.ui.value.preview).performScrollTo().assertIsDisplayed()
            File(folder, "result.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(folder, "receipt.json").writeText(JSONObject().apply {
                put("passed", true); put("task_id", taskId); put("review_id", review.id)
                put("source_run_id", review.sourceRunId); put("model", review.model)
                put("input_tokens", review.inputTokens); put("output_tokens", review.outputTokens)
                put("preview_verified", true); put("auth", "isolated fixture; no production login")
                put("supplier", "synthetic SSE; no paid calls")
            }.toString(2))
        } finally { compose.runOnUiThread { store.clear() }; tokens.clear() }
    }
}
