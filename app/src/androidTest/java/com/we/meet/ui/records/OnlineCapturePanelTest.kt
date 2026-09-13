package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.OnlineCaptureApi
import com.we.meet.data.api.OnlineCaptureNoticeApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.OnlineCaptureRepository
import com.we.meet.data.repository.OnlineCaptureNoticeRepository
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
class OnlineCapturePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "online-ui-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private val api = Fixture()
    private val opened = CopyOnWriteArrayList<String>()
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(8000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun show(guest: Boolean = false, dark: Boolean = false) {
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            OnlineCapturePanel(if (guest) "" else viewer, room, sid, OnlineCaptureRepository(api) { viewer }, { viewer }, { true }) { opened += it }
        } } } }
    }
    private fun click(id: Int, scroll: Boolean = false) {
        await(id)
        val node = compose.onNode(hasText(label(id)) and hasClickAction())
        compose.waitUntil(8000) { !node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        if (scroll) node.performScrollTo()
        node.performClick()
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "online-capture-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun startRequiresConsentAndUsesExactOccurrenceThenOpensExactRecord() {
        show(); click(R.string.online_capture_start, true)
        assertTrue(api.bodies.isEmpty()); screenshot("start-confirm", dialog = true)
        click(R.string.online_capture_confirm); await(R.string.online_capture_recording)
        val input = OnlineCaptureRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals(room, input.roomId); assertEquals(sid, input.livekitRoomSid); assertEquals("start", input.operation)
        click(R.string.online_capture_open, true); assertEquals(listOf(api.record), opened.toList())
    }
    @Test fun stopRemainsAvailableWhenNewStartsDisabledAndWaitsForTranscript() {
        api.capture = "recording"; api.available = false
        show(); click(R.string.online_capture_stop, true)
        compose.onNodeWithText(label(R.string.online_capture_stop_effect)).assertExists()
        click(R.string.online_capture_confirm); await(R.string.online_capture_stopping)
        compose.onNode(hasText(label(R.string.online_capture_stop)) and hasClickAction()).assertIsNotEnabled()
        val input = OnlineCaptureRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals("stop", input.operation); assertEquals(api.id, input.expectedRunId)
    }
    @Test fun unknownStartIsRecoveredWithoutChangingItToStopAfterBackground() {
        api.status = 503
        show(); click(R.string.online_capture_start, true); click(R.string.online_capture_confirm); await(R.string.summary_controls_reconcile)
        val original = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile); assertEquals(1, api.bodies.size)
        api.status = 200
        click(R.string.summary_controls_reconcile, true); await(R.string.online_capture_recording)
        assertEquals(original, api.bodies.last())
    }
    @Test fun currentRunChangeDisablesOldStartConfirmation() {
        show(); click(R.string.online_capture_start, true)
        api.capture = "recording"
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.online_capture_recording)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label(R.string.online_capture_confirm)) and hasClickAction()).assertIsNotEnabled()
        assertTrue(api.bodies.isEmpty())
    }
    @Test fun guestCannotReadPrivateControl() {
        show(guest = true); await(R.string.online_capture_guest)
        assertEquals(0, api.reads); assertTrue(api.bodies.isEmpty())
    }
    @Test fun nonManagerWithReadAccessCannotStartRecording() {
        api.manager = false
        show(); await(R.string.online_capture_manager)
        compose.onNodeWithText(label(R.string.online_capture_start)).assertDoesNotExist()
        assertTrue(api.bodies.isEmpty())
    }
    @Test fun noticeUsesJoinTokenAndBackgroundFailureShowsUnknownInsteadOfStaleRecording() {
        var denied = false
        val noticeApi = object : OnlineCaptureNoticeApi {
            override suspend fun notice(roomId: String, sid: String, authorization: String): OnlineCaptureNoticeDto {
                assertEquals(room, roomId); assertEquals(this@OnlineCapturePanelTest.sid, sid); assertEquals("Bearer fixture.join.token", authorization)
                if (denied) throw HttpException(Response.error<Any>(403, "{}".toResponseBody()))
                return OnlineCaptureNoticeDto("recording")
            }
        }
        compose.setContent { WeMeetTheme(darkTheme = true) { OnlineCaptureNotice(room, sid, "fixture.join.token", OnlineCaptureNoticeRepository(noticeApi)) { true } } }
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.online_capture_recording), substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("notice-dark")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED); denied = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(8000) { compose.onAllNodesWithText(label(R.string.online_capture_unknown_state), substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(label(R.string.online_capture_recording), substring = true).assertCountEquals(0)
        assertTrue(api.bodies.isEmpty())
    }
    private class Fixture : OnlineCaptureApi {
        val id = UUID.randomUUID().toString()
        val record = UUID.randomUUID().toString()
        @Volatile var status = 200
        @Volatile var available = true
        @Volatile var manager = true
        @Volatile var capture: String? = null
        @Volatile var reads = 0
        val bodies = CopyOnWriteArrayList<String>()
        private fun row(state: String) = OnlineCaptureRunDto(id, record, state, "", null, null, "unverified")
        override suspend fun state(roomId: String, sid: String): OnlineCaptureStateDto { reads++; return OnlineCaptureStateDto(available, manager, capture?.let(::row)) }
        override suspend fun control(body: RequestBody): OnlineCaptureReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            val request = OnlineCaptureRepository.requestAdapter.fromJson(text)!!
            capture = if (request.operation == "start") "recording" else "stopping"
            if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody()))
            return OnlineCaptureReceiptDto(row(if (request.operation == "start") "starting" else "stopping"), row(capture!!), bodies.size > 1)
        }
    }
}
