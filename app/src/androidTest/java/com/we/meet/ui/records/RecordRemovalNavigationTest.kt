package com.we.meet.ui.records

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingRecordApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingRecordRepository
import com.we.meet.ui.nav.Routes
import com.we.meet.ui.nav.openLibraryAfterRecordRemoval
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordRemovalNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var nav: NavHostController
    private var writes = 0
    private val api = java.lang.reflect.Proxy.newProxyInstance(MeetingRecordApi::class.java.classLoader, arrayOf(MeetingRecordApi::class.java)) { _, method, args ->
        when (method.name) {
            "record" -> RecordDto(id, "upload", "Removal test", "2026-09-20T00:00:00Z", 1,
                RecordCapabilitiesDto(readSummary = true, readTranscript = true, trash = true), lifecycleRevision = 0)
            "originals" -> RecordPageDto<RecordOriginalSegmentDto>(emptyList())
            "lifecycle" -> {
                assertEquals(id, args!![0])
                assertEquals(RecordLifecycleRequest("trashed", 0), args[1])
                writes++
                RecordLifecycleDto(id, "Removal test", "upload", "2026-09-20T01:00:00Z", 1)
            }
            else -> error("Unexpected request: ${method.name}")
        }
    } as MeetingRecordApi

    private fun click(label: Int) {
        val text = context.getString(label)
        compose.waitUntil(8000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(text)
        if (label == R.string.records_info) node.performScrollTo()
        node.performClick()
    }

    private fun verifyRemoval(parent: String) {
        val repository = MeetingRecordRepository(api) { "owner" }
        compose.setContent { WeMeetTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { Text("Home fixture") }
                composable("upload") { Text("Upload fixture") }
                composable("capture") { Text("Capture fixture") }
                composable(Routes.RECORD_LIBRARY, arguments = listOf(navArgument("summaries") { type = NavType.BoolType; defaultValue = false })) {
                    Text("Library fixture")
                }
                composable("detail") {
                    RecordDetailScreen(repository, "owner", this@RecordRemovalNavigationTest.id, onBack = { nav.popBackStack() },
                        onRemoved = { nav.openLibraryAfterRecordRemoval() })
                }
            }
        } }
        compose.runOnIdle { nav.navigate(parent); nav.navigate("detail") }
        click(R.string.records_info)
        click(R.string.record_trash_remove)
        click(R.string.record_trash_cancel)
        compose.runOnIdle { assertEquals(0, writes); assertEquals("detail", nav.currentDestination?.route) }
        click(R.string.record_trash_remove)
        click(R.string.record_trash_confirm_remove)
        compose.waitUntil(8000) { compose.onAllNodesWithText("Library fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(Routes.HOME, nav.previousBackStackEntry?.destination?.route)
            assertTrue(nav.popBackStack())
        }
        compose.onNodeWithText("Home fixture").assertIsDisplayed()
    }

    @Test fun removalFromUploadDoesNotReturnToUnavailableUpload() = verifyRemoval("upload")
    @Test fun removalFromCaptureDoesNotReturnToStoppedCapture() = verifyRemoval("capture")
    @Test fun removalFromMinutesOpensOneFreshLibrary() = verifyRemoval("meeting_records?summaries=true")
}
