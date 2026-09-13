package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.R
import com.we.meet.data.api.CloudRecordingApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.CloudRecordingRepository
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class CloudRecordingPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "cloud-ui-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private val api = Fixture()
    private val repository = CloudRecordingRepository(api) { viewer }
    private val sameSource = mutableStateOf(true)
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(10000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(guest: Boolean = false, dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface {
            CloudRecordingPanel(if (guest) "" else viewer, room, sid, repository, { viewer }) { sameSource.value }
        } } }
    }
    private fun click(id: Int, scroll: Boolean = false) {
        await(id)
        val node = compose.onNode(hasText(label(id)) and hasClickAction())
        compose.waitUntil(8000) { !node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        if (scroll) node.performScrollTo()
        node.performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        File(context.getExternalFilesDir(null), "cloud-recording-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun startRequiresConfirmationAndAcceptedDoesNotClaimActive() {
        show(); click(R.string.cloud_recording_start, true)
        assertTrue(api.bodies.isEmpty()); screenshot("start-confirm-light")
        click(R.string.cloud_recording_confirm); await(R.string.cloud_recording_processing)
        compose.onNodeWithText(label(R.string.cloud_recording_active)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cloud_recording_stop)).assertDoesNotExist()
        val input = CloudRecordingRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals(room, input.roomId); assertEquals(sid, input.livekitRoomSid); assertEquals("start", input.operation)
        api.recording = "active"; api.pending = null
        await(R.string.cloud_recording_stop)
    }

    @Test fun rollbackCanStopKnownRecordingWithoutStoppingMeetingOrAiCapture() {
        api.recording = "active"; api.available = false
        show(dark = true); click(R.string.cloud_recording_stop, true)
        compose.onNodeWithText(label(R.string.cloud_recording_stop_effect)).assertExists()
        screenshot("stop-confirm-dark")
        click(R.string.cloud_recording_confirm); await(R.string.cloud_recording_processing)
        compose.onNodeWithText(label(R.string.cloud_recording_saved)).assertDoesNotExist()
        val input = CloudRecordingRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals("stop", input.operation); assertEquals(api.record, input.expectedRecordingId)
    }

    @Test fun unknownStartSurvivesBackgroundAndOnlyReplaysOnExplicitClick() {
        api.status = 503
        show(); click(R.string.cloud_recording_start, true); click(R.string.cloud_recording_confirm)
        await(R.string.cloud_recording_reconcile); val original = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.cloud_recording_reconcile); assertEquals(1, api.bodies.size)
        compose.onNodeWithText(label(R.string.cloud_recording_off)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cloud_recording_status_unknown)).assertExists()
        screenshot("recover-light")
        api.status = 202
        click(R.string.cloud_recording_reconcile, true); await(R.string.cloud_recording_processing)
        assertEquals(original, api.bodies.last())
    }

    @Test fun newerRecordingInvalidatesOpenConfirmation() {
        show(); click(R.string.cloud_recording_start, true)
        api.recording = "active"
        await(R.string.cloud_recording_active)
        compose.onNode(hasText(label(R.string.cloud_recording_confirm)) and hasClickAction()).assertIsNotEnabled()
        assertTrue(api.bodies.isEmpty())
    }

    @Test fun sourceChangeDisablesConfirmationWithoutDispatch() {
        show(); click(R.string.cloud_recording_start, true)
        compose.runOnIdle { sameSource.value = false }
        compose.onNode(hasText(label(R.string.cloud_recording_confirm)) and hasClickAction()).assertIsNotEnabled()
        assertTrue(api.bodies.isEmpty())
    }

    @Test fun guestNeverReadsPrivateState() {
        show(guest = true); await(R.string.cloud_recording_guest)
        assertEquals(0, api.reads); assertTrue(api.bodies.isEmpty())
    }

    @Test fun permissionLossOnResumeClearsStaleControls() {
        api.recording = "active"
        show(); await(R.string.cloud_recording_stop)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.readDenied = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.cloud_recording_unavailable)
        compose.onNodeWithText(label(R.string.cloud_recording_stop)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cloud_recording_active)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }

    @Test fun serverUnknownDoesNotOfferAnotherStart() {
        api.recording = "initiated"; api.pending = "start"; api.unknown = true
        show(dark = true); await(R.string.cloud_recording_worker_unknown)
        screenshot("worker-unknown-dark")
        compose.onNodeWithText(label(R.string.cloud_recording_start)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.cloud_recording_reconcile)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }

    private class Fixture : CloudRecordingApi {
        val record = UUID.randomUUID().toString()
        private val session = UUID.randomUUID().toString()
        private val command = UUID.randomUUID().toString()
        @Volatile var recording: String? = null
        @Volatile var pending: String? = null
        @Volatile var unknown = false
        @Volatile var available = true
        @Volatile var status = 202
        @Volatile var readDenied = false
        @Volatile var reads = 0
        val bodies = CopyOnWriteArrayList<String>()
        private val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Map::class.java)
        private fun row(status: String) = CloudRecordingDto(record, session, "screen_recording", status, "2026-09-13T00:00:00Z")
        override suspend fun state(roomId: String, sid: String): CloudRecordingStateDto {
            reads++
            if (readDenied) throw HttpException(Response.error<Any>(403, "{}".toResponseBody()))
            val pendingValue = pending
            val recordingValue = recording
            return CloudRecordingStateDto(CloudRecordingSourceDto(roomId, sid, session), available,
                available && recordingValue !in setOf("initiated", "active") && pendingValue == null,
                recordingValue == "active" && pendingValue == null, false, unknown, recordingValue?.let(::row),
                pendingValue?.let { CloudRecordingPendingDto(command, it, if (unknown) "unknown" else "accepted", "") })
        }
        override suspend fun control(body: RequestBody): CloudRecordingReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            val request = requireNotNull(CloudRecordingRepository.requestAdapter.fromJson(text))
            if (status != 202) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            pending = request.operation
            recording = if (request.operation == "start") "initiated" else "active"
            val key = requireNotNull(json.fromJson(text)?.get("key") as? String)
            return CloudRecordingReceiptDto(CloudRecordingCommandDto(command, key, session,
                CloudRecordingPayloadDto(request.operation, request.expectedRecordingId), row(recording!!), "accepted", ""), state(request.roomId, request.livekitRoomSid), bodies.size > 1)
        }
    }
}
