package com.we.meet.ui.records

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.MeetingReviewApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.data.repository.MeetingReviewRepository
import com.we.meet.ui.nav.Routes
import com.we.meet.ui.theme.WeMeetTheme
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Real navigation and Retrofit, with private fixture HTTP confined to this emulator. */
class PinnedSummaryShareNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val recordId = "11111111-1111-4111-8111-111111111111"
    private val version = "22222222-2222-4222-8222-222222222222"
    private val snapshot = "33333333-3333-4333-8333-333333333333"
    private val base = "https://meet.example"
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var missing = false
    @Volatile private var revoked = false
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val record = RecordDto(recordId, "audio_recording", "Shared history", "2026-10-11T00:00:00Z", 4,
        RecordCapabilitiesDto(readSummary = true, readTranscript = false))
    private val human = HumanReviewDto(version, 1, "44444444-4444-4444-8444-444444444444", null, snapshot, null,
        "2026-10-11T00:00:00Z", HumanContentDto("Frozen original human identity", emptyList(), emptyList(),
            listOf(HumanActionDto("Original follow-up", emptyList(), "Original owner", "Original due")), emptyList()), "human", 1, identityUpdated = true)
    private val ai = RecordSummaryVersionDto(version, "final", snapshot, 1, false, "2026-10-11T00:00:00Z", "complete",
        content = RecordSummaryContentDto("Frozen original AI identity", emptyList(), emptyList(), emptyList(), emptyList()), identityUpdated = true)
    private fun <T> json(type: Class<T>, value: T) = MockResponse().setHeader("Content-Type", "application/json").setBody(moshi.adapter(type).toJson(value))
    private fun awaitText(value: String) = compose.waitUntil(8000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun label(id: Int) = compose.activity.getString(id)

    private fun show(kind: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += path
                val root = "/api/v1.0/meeting-records/$recordId/"
                if (revoked) return MockResponse().setResponseCode(404).setBody("{}")
                return when {
                    path == root -> json(RecordDto::class.java, record)
                    path == "${root}human-summary/history/$version/" ->
                        if (missing) MockResponse().setResponseCode(404).setBody("{}") else json(HumanReviewDto::class.java, human)
                    path.startsWith("${root}summary-versions/?") && request.requestUrl?.queryParameter("version_id") == version ->
                        MockResponse().setHeader("Content-Type", "application/json").setBody("{\"results\":[${moshi.adapter(RecordSummaryVersionDto::class.java).toJson(ai)}],\"next_cursor\":null}")
                    else -> MockResponse().setResponseCode(500).setBody("{\"detail\":\"Unexpected fixture read\"}")
                }
            }
        }
        server.start()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
        val records = MeetingRecordRepository(retrofit.create(MeetingRecordApi::class.java)) { "fixture-viewer" }
        val reviews = MeetingReviewRepository(retrofit.create(MeetingReviewApi::class.java)) { "fixture-viewer" }
        val link = RecordLinks.parse(RecordLinks.material(recordId, base, "minutes",
            summaryId = if (kind == "summary") version else null, humanId = if (kind == "human") version else null), base)!!
        compose.setContent { WeMeetTheme {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    TextButton(onClick = { nav.navigate(Routes.recordDetail(link.recordId, link.summaryId, humanId = link.humanId)) }) { Text("Open shared history") }
                }
                composable(Routes.RECORD_DETAIL, arguments = listOf(
                    navArgument("recordId") { type = NavType.StringType },
                    navArgument("summary") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("human") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null },
                )) { entry ->
                    RecordDetailScreen(records, "fixture-viewer", entry.arguments?.getString("recordId").orEmpty(), { nav.popBackStack() },
                        summaryVersionId = entry.arguments?.getString("summary"), humanVersionId = entry.arguments?.getString("human"), reviewRepository = reviews)
                }
            }
        } }
        compose.onNodeWithText("Open shared history").performClick()
    }

    @After fun close() { server.shutdown() }

    @Test fun humanShareOpensExactReadOnlyRevisionAndDropsPrivateBodyAfterRevocation() {
        show("human")
        awaitText("Frozen original human identity")
        compose.onNodeWithText(label(R.string.records_identity_updated)).assertExists()
        compose.onNodeWithText("Original owner · Original due").assertExists()
        compose.onNodeWithText(label(R.string.human_summary_edit)).assertDoesNotExist()
        assertTrue(requests.any { it.endsWith("human-summary/history/$version/") })
        assertTrue(requests.none { "summary-versions" in it || it.endsWith("human-summary/") || "original-segments" in it })
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        revoked = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitText(label(R.string.records_unavailable))
        compose.onNodeWithText("Frozen original human identity").assertDoesNotExist()
        compose.onNodeWithText("Shared history").assertDoesNotExist()
    }

    @Test fun missingHumanShareNeverSubstitutesCurrentHumanOrAi() {
        missing = true; show("human")
        awaitText(label(R.string.human_summary_read_error))
        compose.onNodeWithText("Frozen original human identity").assertDoesNotExist()
        assertTrue(requests.none { "summary-versions" in it || it.endsWith("human-summary/") })
    }

    @Test fun aiSharePreservesFixedVersionInTheNativeWorkspace() {
        show("summary")
        awaitText("Frozen original AI identity")
        compose.onNodeWithText(label(R.string.records_identity_updated)).assertExists()
        assertTrue(requests.any { "version_id=$version" in it })
        assertTrue(requests.none { "human-summary" in it || "original-segments" in it })
    }
}
