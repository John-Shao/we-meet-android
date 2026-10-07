package com.we.meet.ui.work

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.WorkApi
import com.we.meet.data.repository.WorkRepository
import com.we.meet.ui.theme.WeMeetTheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class WorkReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun httpReviewProgressEvidenceAndSourceRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val done = AtomicBoolean(false)
        val revoked = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<String>()
        val run = "b435958e-79be-4111-8f83-d36a48e3ef56"
        val sha = "a".repeat(64)
        val task = """{"id":"fixture-task","goal":"Review desktop result","runs":[{"id":"$run","status":"succeeded","execution_target":"local","workspace_label":"Fixture Project"}]}"""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                val body = when (request.path) {
                    "/api/v1.0/work/capabilities/" -> """{"remote_agent_enabled":false,"review_enabled":true}"""
                    "/api/v1.0/work/tasks/?page=1" -> """{"results":[$task],"next":null}"""
                    "/api/v1.0/work/tasks/fixture-task/" -> task
                    "/api/v1.0/work/runs/$run/files/" -> """[{"name":"report.md","sha256":"$sha"}]"""
                    "/api/v1.0/work/runs/$run/reviews/" -> {
                        if (revoked.get()) return MockResponse().setResponseCode(409).setBody("""{"code":"source_access_revoked"}""")
                        val report = if (done.get()) """{"verdict":"needs_changes","summary":"Review marker 6107","findings":[{"severity":"warning","message":"Verify the claim","evidence":[{"file":"result-01.md","sha256":"$sha","quote":"<script>literal evidence</script>"}]}],"missing_information":["Missing acceptance criteria"]}""" else "{}"
                        val usage = if (done.get()) "100" to "30" else "null" to "null"
                        """[{"id":"4807b44f-16b3-4583-bde3-9c4d7d1cbfae","source_run_id":"$run","status":"${if (done.get()) "succeeded" else "queued"}","model":"qwen3.8-flash","selection":[{"name":"report.md","sha256":"$sha"}],"snapshot":[{"name":"result-01.md","sha256":"$sha"}],"reserved_tokens":4096,"input_tokens":${usage.first},"output_tokens":${usage.second},"report":$report}]"""
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build().create(WorkApi::class.java)
        lateinit var vm: WorkViewModel
        val store = ViewModelStore()
        compose.runOnUiThread { vm = WorkViewModel(WorkRepository(api), SavedStateHandle()); store.put("work", vm) }
        try {
            compose.setContent { WeMeetTheme { WorkScreen(vm) {} } }
            compose.waitUntil(10000) { !vm.ui.value.loading }
            compose.onNodeWithText("Review desktop result").performScrollTo().performClick()
            compose.waitUntil(10000) { vm.ui.value.reviews.isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.work_review_queued)).performScrollTo().assertIsDisplayed()
            done.set(true)
            compose.runOnUiThread { vm.poll() }
            compose.waitUntil(10000) { vm.ui.value.reviews.firstOrNull()?.status == "succeeded" }
            compose.onNodeWithText("qwen3.8-flash").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.work_review_usage, 100, 30)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("<script>literal evidence</script>").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.work_review_evidence, "result-01.md", sha)).performScrollTo().assertIsDisplayed()
            val folder = File(context.getExternalFilesDir(null), "work-fixture").apply { mkdirs() }
            File(folder, "work-review.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            revoked.set(true)
            compose.runOnUiThread { vm.refresh() }
            compose.waitUntil(10000) { vm.ui.value.reviewsUnavailable }
            compose.onNodeWithText("<script>literal evidence</script>").assertDoesNotExist()
            compose.onNodeWithText("Review marker 6107").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.work_review_unavailable)).performScrollTo().assertIsDisplayed()
            assertTrue(requests.any { it == "GET /api/v1.0/work/runs/$run/reviews/" })
            assertTrue(requests.all { it.startsWith("GET ") })
            compose.onNodeWithText(context.getString(R.string.work_back_list)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.work_dispatch_title)).assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() }; server.shutdown() }
    }
}
