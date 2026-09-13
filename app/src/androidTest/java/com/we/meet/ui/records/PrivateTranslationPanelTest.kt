package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.data.api.MeetingTranslationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.PrivateTranslationCommand
import com.we.meet.data.repository.MeetingTranslationRepository
import com.we.meet.livekit.*
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
class PrivateTranslationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "translation-ui-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private val api = Fixture()
    private val transport = Transport()
    private var panel by mutableStateOf(true)
    private var microphone by mutableStateOf(true)
    private lateinit var controller: PrivateTranslationController
    private fun label(id: Int) = context.getString(id)
    private fun await(id: Int) { compose.waitUntil(9000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(id: Int, scroll: Boolean = true) {
        await(id)
        val node = compose.onNode(hasText(label(id)) and hasClickAction())
        compose.waitUntil(9000) { !node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        if (scroll) node.performScrollTo()
        node.performClick()
    }
    private fun show(guest: Boolean = false, dark: Boolean = false) {
        val repo = MeetingTranslationRepository(api) { viewer }
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface {
            controller = rememberPrivateTranslation(if (guest) "" else viewer, room, sid, repo, { viewer }, transport)
            if (panel) PrivateTranslationPanel(controller, if (guest) "" else viewer, room, sid, transport.localSid, microphone)
        } } }
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "private-translation-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun startingRequiresConsentAndDoesNotImplicitlyPlayOrSaveTranslation() {
        show(); click(R.string.translation_start)
        assertTrue(api.bodies.isEmpty()); screenshot("start-confirm", true)
        compose.onNodeWithText(label(R.string.translation_no_save)).assertExists()
        click(R.string.online_capture_confirm, false); await(R.string.translation_running)
        val request = MeetingTranslationRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals(room, request.roomId); assertEquals(sid, request.livekitRoomSid); assertEquals(api.connection, request.sourceParticipationId)
        assertEquals("zh", request.source); assertEquals("en", request.target); assertEquals(false, request.saveTranslations)
        assertTrue(transport.latest.isEmpty())
        click(R.string.translation_listen)
        compose.waitUntil(9000) { transport.latest.isNotEmpty() }
        compose.runOnIdle { panel = false }
        assertTrue(transport.latest.isNotEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(transport.latest.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { panel = true }; await(R.string.translation_running)
        assertTrue(transport.latest.isEmpty()); assertEquals(1, api.bodies.size)
    }
    @Test fun bidirectionalControlsUseMeetingMicAndWaitForReadyCompletion() {
        api.current = api.row.copy(configuration = api.config.copy(mode = "push_to_talk"))
        microphone = false
        show(dark = true); await(R.string.translation_mic_off)
        compose.onNode(hasText(label(R.string.translation_speak_zh)) and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { microphone = true }
        click(R.string.translation_speak_zh)
        compose.waitUntil(9000) { transport.commands.any { String(it.payload).contains("\"action\":\"begin\"") } }
        click(R.string.translation_end_turn); await(R.string.translation_wait_turn)
        compose.onNode(hasText(label(R.string.translation_speak_en)) and hasClickAction()).assertIsNotEnabled()
        screenshot("manual-dark")
        transport.waiting = false
        click(R.string.translation_speak_en)
        val spoken = transport.commands.filter { !String(it.payload).contains("\"action\":\"sync\"") }
        assertEquals(3, spoken.size)
        assertTrue(String(spoken[0].payload).contains("\"sequence\":1"))
        assertTrue(String(spoken[1].payload).contains("\"sequence\":2"))
        assertTrue(String(spoken[2].payload).contains("\"direction\":\"reverse\""))
    }
    @Test fun uncertainStartSurvivesBackgroundWithItsOriginalConsentAndBody() {
        api.writeStatus = 503
        show(); click(R.string.translation_manual); click(R.string.translation_save); click(R.string.translation_start); click(R.string.online_capture_confirm, false)
        await(R.string.summary_controls_reconcile); val original = api.bodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.summary_controls_reconcile); assertEquals(1, api.bodies.size)
        api.writeStatus = 200
        click(R.string.summary_controls_reconcile); await(R.string.translation_running)
        assertEquals(original, api.bodies.last()); assertTrue(transport.latest.isEmpty())
    }
    @Test fun stopRemainsExplicitWhenNewStartsAreDisabled() {
        api.current = api.row; api.available = false
        show(); click(R.string.translation_stop)
        assertTrue(api.bodies.isEmpty()); compose.onNodeWithText(label(R.string.translation_stop_effect)).assertExists()
        click(R.string.online_capture_confirm, false); await(R.string.translation_stopping)
        val request = MeetingTranslationRepository.requestAdapter.fromJson(api.bodies.single())!!
        assertEquals("stop", request.operation); assertNull(request.audio); assertNull(request.sourceParticipationId)
        assertTrue(transport.latest.isEmpty())
    }
    @Test fun sourceLossClearsGrantsAndControls() {
        api.current = api.row
        show(); click(R.string.translation_listen)
        compose.waitUntil(9000) { transport.latest.isNotEmpty() }
        transport.connected = false
        await(R.string.translation_unavailable); assertTrue(transport.latest.isEmpty())
        compose.onNodeWithText(label(R.string.translation_stop)).assertDoesNotExist()
    }
    @Test fun backgroundEndsHeldSpeechAndDoesNotBeginAgainOnResume() {
        api.current = api.row.copy(configuration = api.config.copy(mode = "push_to_talk"))
        show(); click(R.string.translation_speak_zh)
        compose.waitUntil(9000) { transport.commands.any { String(it.payload).contains("\"action\":\"begin\"") } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(transport.commands.any { String(it.payload).contains("\"action\":\"end\"") })
        assertTrue(transport.latest.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.translation_wait_turn)
        assertEquals(1, transport.commands.count { String(it.payload).contains("\"action\":\"begin\"") })
    }
    @Test fun permissionLossDropsPlaybackWithoutAutomaticReadOrWriteRetries() {
        api.current = api.row
        show(); click(R.string.translation_listen)
        compose.waitUntil(9000) { transport.latest.isNotEmpty() }
        api.readStatus = 403
        compose.runOnIdle { controller.refresh() }
        await(R.string.translation_unavailable); assertTrue(transport.latest.isEmpty())
        assertTrue(api.bodies.isEmpty())
        compose.onNodeWithText(label(R.string.translation_stop)).assertDoesNotExist()
    }
    @Test fun guestDoesNotReadOrStartPersonalTranslation() {
        show(guest = true); await(R.string.translation_guest)
        assertEquals(0, api.reads); assertTrue(api.bodies.isEmpty()); assertTrue(transport.commands.isEmpty())
    }
    @Test fun recoveredUnfinishedSpeechIsEndedWithoutStartingAnotherTurn() {
        api.current = api.row.copy(configuration = api.config.copy(mode = "push_to_talk"))
        transport.restoreHeld()
        show(); await(R.string.translation_wait_turn)
        assertEquals(1, transport.commands.count { String(it.payload).contains("\"action\":\"end\"") })
        assertFalse(transport.commands.any { String(it.payload).contains("\"action\":\"begin\"") })
        assertTrue(transport.latest.isEmpty())
    }
    private class Fixture : MeetingTranslationApi {
        val id = UUID.randomUUID().toString()
        val connection = UUID.randomUUID().toString()
        val record = UUID.randomUUID().toString()
        val config = PrivateTranslationConfigurationDto("zh", "en", "simultaneous", true, "qwen3.5-livetranslate-flash-realtime", "controller_only")
        val row = PrivateTranslationRunDto(id, 1, "translating", config, "PA_me", "")
        @Volatile var current: PrivateTranslationRunDto? = null
        @Volatile var available = true
        @Volatile var writeStatus = 200
        @Volatile var reads = 0
        @Volatile var readStatus = 200
        val bodies = CopyOnWriteArrayList<String>()
        private var frozen: PrivateTranslationRunDto? = null
        override suspend fun state(roomId: String, sid: String): PrivateTranslationStateDto {
            reads++
            if (readStatus != 200) throw HttpException(Response.error<Any>(readStatus, "{}".toResponseBody()))
            return PrivateTranslationStateDto(available, true, listOf("zh", "en"), current, listOf(TranslationConnectionDto(connection, "PA_me")))
        }
        override suspend fun control(body: RequestBody): PrivateTranslationReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); bodies += text
            val request = MeetingTranslationRepository.requestAdapter.fromJson(text)!!
            val replay = frozen != null
            if (frozen == null) {
                current = if (request.operation == "start") row.copy(configuration = config.copy(source = request.source!!, target = request.target!!, mode = request.mode!!, audio = request.audio!!, archiveRecordId = if (request.saveTranslations == true) record else null)) else row.copy(state = "stopping")
                frozen = current!!.copy(state = if (request.operation == "start") "starting" else "stopping")
            }
            if (writeStatus != 200) throw HttpException(Response.error<Any>(writeStatus, "{}".toResponseBody()))
            return PrivateTranslationReceiptDto(frozen!!, current, replay)
        }
    }
    private inner class Transport : PrivateTranslationTransport {
        override val localSid = "PA_me"
        @Volatile var connected = true
        @Volatile var waiting = false
        @Volatile var latest: List<TranslationAudioGrant> = emptyList()
        val commands = CopyOnWriteArrayList<PrivateTranslationCommand>()
        private val events = MutableSharedFlow<TranslationPacket>(extraBufferCapacity = 16)
        private var sequence = 0L
        private var held: String? = null
        fun restoreHeld() { held = "forward"; sequence = 1 }
        override fun current() = connected
        override fun agents() = listOf("translation-${api.id}-worker")
        override fun packets(): Flow<TranslationPacket> = events
        override suspend fun send(command: PrivateTranslationCommand) {
            commands += command
            val text = String(command.payload)
            if (text.contains("\"action\":\"begin\"")) { sequence++; held = if (text.contains("\"direction\":\"reverse\"")) "reverse" else "forward" }
            if (text.contains("\"action\":\"end\"")) { sequence++; held = null; waiting = true }
            val direction = held?.let { "\"$it\"" } ?: "null"
            val body = """{"run_id":"${api.id}","generation":1,"type":"ready","sequence":$sequence,"direction":$direction,"awaiting":$waiting,"audio_track_sid":"TR_audio"}"""
            events.emit(TranslationPacket(body.toByteArray(), "translation-${api.id}-worker", "PA_agent", true))
        }
        override fun grants(values: List<TranslationAudioGrant>) { latest = values }
    }
}
