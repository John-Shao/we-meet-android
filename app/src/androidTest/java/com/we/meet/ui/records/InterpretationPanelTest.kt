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
import com.we.meet.data.api.MeetingInterpretationApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.MeetingInterpretationRepository
import com.we.meet.livekit.*
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
class InterpretationPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "interpretation-ui-${UUID.randomUUID()}"
    private val room = UUID.randomUUID().toString()
    private val sid = "RM_current"
    private val api = Fixture()
    private val transport = Transport()
    private lateinit var controller: InterpretationController
    private var beforeListen = 0
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
        val repo = MeetingInterpretationRepository(api) { viewer }
        compose.setContent { WeMeetTheme(darkTheme = dark) { Surface {
            controller = rememberInterpretation(if (guest) "" else viewer, room, sid, repo, { viewer }, transport)
            controller.beforeListen = { beforeListen++ }
            InterpretationPanel(controller, if (guest) "" else viewer, room, sid, transport::speakerName)
        } } }
    }
    private fun screenshot(name: String, dialog: Boolean = false) {
        File(context.getExternalFilesDir(null), "interpretation-$name.png").outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun listen() {
        click(R.string.interpretation_listen_en)
        compose.waitUntil(9000) { transport.latest.isNotEmpty() }
    }
    @Test fun managerStartNeedsConsentAndDoesNotJoinOrRetainByDefault() {
        api.channel = null
        show(); click(R.string.interpretation_start_en)
        assertTrue(api.channelBodies.isEmpty()); screenshot("start-confirm", true)
        compose.onNodeWithText(label(R.string.interpretation_start_effect)).assertExists()
        compose.onNodeWithText(label(R.string.translation_no_save)).assertExists()
        click(R.string.online_capture_confirm, false); await(R.string.interpretation_prepared)
        val request = MeetingInterpretationRepository.channelAdapter.fromJson(api.channelBodies.single())!!
        assertEquals("start", request.operation); assertEquals("en", request.target); assertEquals(false, request.saveTranslations)
        assertNull(request.expectedChannelId); assertTrue(api.listenBodies.isEmpty()); assertTrue(transport.latest.isEmpty())
    }
    @Test fun participantLeavesOwnSubscriptionWithoutClosingChannel() {
        api.manager = false
        show(dark = true); listen(); screenshot("listener-dark")
        compose.onNodeWithText(label(R.string.interpretation_stop_en)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.interpretation_start_zh)).assertDoesNotExist()
        assertEquals(1, beforeListen)
        click(R.string.interpretation_leave); await(R.string.interpretation_listen_en)
        compose.waitUntil(9000) { api.listenBodies.size == 2 && controller.pendingListen == null && !controller.busy }
        assertEquals("leave", MeetingInterpretationRepository.listenAdapter.fromJson(api.listenBodies.last())!!.operation)
        assertTrue(transport.latest.isEmpty()); assertTrue(api.channelBodies.isEmpty()); assertEquals("translating", api.channel!!.state)
    }
    @Test fun existingServerSubscriptionNeverAutoPlaysAndBackgroundRequiresNewSelection() {
        api.subscription = api.sub
        show(); await(R.string.interpretation_listen_en)
        assertTrue(transport.latest.isEmpty()); assertTrue(api.listenBodies.isEmpty())
        listen(); assertEquals(1, beforeListen)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(transport.latest.isEmpty())
        val renews = api.renews
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.interpretation_listen_en)
        assertTrue(transport.latest.isEmpty()); assertEquals(1, api.listenBodies.size); assertEquals(renews, api.renews)
    }
    @Test fun uncertainJoinUsesOriginalRequestAndReplayedReceiptNeverResumesSound() {
        api.listenStatus = 503
        show(); click(R.string.interpretation_listen_en); await(R.string.interpretation_retry_listen)
        val original = api.listenBodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.interpretation_retry_listen); assertEquals(1, api.listenBodies.size)
        api.listenStatus = 200
        click(R.string.interpretation_retry_listen)
        compose.waitUntil(9000) { api.listenBodies.size == 2 && controller.pendingListen == null && !controller.busy }
        assertEquals(original, api.listenBodies.last()); assertTrue(transport.latest.isEmpty()); assertEquals(0, beforeListen)
        listen(); assertEquals(3, api.listenBodies.size); assertEquals(1, beforeListen)
    }
    @Test fun uncertainChannelCommandRetainsOriginalChoiceAcrossBackground() {
        api.channel = null; api.channelStatus = 503
        show(); click(R.string.interpretation_start_en); click(R.string.online_capture_confirm, false)
        await(R.string.interpretation_retry_channel); val original = api.channelBodies.single()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        api.available = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await(R.string.interpretation_retry_channel); assertEquals(1, api.channelBodies.size)
        api.channelStatus = 200
        click(R.string.interpretation_retry_channel)
        compose.waitUntil(9000) { api.channelBodies.size == 2 && controller.pendingChannel == null && !controller.busy }
        assertEquals(original, api.channelBodies.last()); assertTrue(api.listenBodies.isEmpty()); assertTrue(transport.latest.isEmpty())
    }
    @Test fun renewalFailureSilencesLocallyWithoutClosingSharedChannel() {
        show(); listen(); api.renewStatus = 403
        await(R.string.interpretation_error)
        assertTrue(transport.latest.isEmpty()); assertNull(controller.session.desired)
        assertEquals(1, api.listenBodies.size); assertTrue(api.channelBodies.isEmpty()); assertEquals("translating", api.channel!!.state)
    }
    @Test fun departedSourceRevokesAudioBeforeNextHttpRead() {
        show(); listen(); transport.sourcePresent = false
        compose.waitUntil(2000) { transport.latest.isEmpty() }
        assertEquals(1, api.listenBodies.size); assertTrue(api.channelBodies.isEmpty())
    }
    @Test fun stoppingChannelExplainsAllListenersEvenWhenStartsUnavailable() {
        api.available = false
        show(); click(R.string.interpretation_stop_en)
        assertTrue(api.channelBodies.isEmpty())
        compose.onNodeWithText(label(R.string.interpretation_stop_effect)).assertExists()
        click(R.string.online_capture_confirm, false); await(R.string.translation_stopping)
        val request = MeetingInterpretationRepository.channelAdapter.fromJson(api.channelBodies.single())!!
        assertEquals("stop", request.operation); assertEquals(api.row.id, request.expectedChannelId); assertNull(request.saveTranslations)
    }
    @Test fun guestDoesNotReadOrJoinInterpretation() {
        show(guest = true); await(R.string.interpretation_guest)
        assertEquals(0, api.reads); assertTrue(api.channelBodies.isEmpty()); assertTrue(api.listenBodies.isEmpty())
    }
    @Test fun localMuteRemainsAvailableDuringAnotherChannelOperation() {
        show(); listen()
        compose.runOnIdle { controller.busy = true }
        click(R.string.translation_mute)
        assertTrue(transport.latest.isEmpty()); assertNotNull(controller.session.desired)
        assertEquals(1, beforeListen); assertEquals(1, api.listenBodies.size)
        compose.onNode(hasText(label(R.string.translation_listen)) and hasClickAction()).assertIsNotEnabled()
    }
    private class Fixture : MeetingInterpretationApi {
        val connection = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        val row = InterpretationChannelDto(UUID.randomUUID().toString(), "en", 1, "translating", "", null)
        val sub = InterpretationSubscriptionDto(UUID.randomUUID().toString(), row.id, connection, 1, true, 20.0, "2026-09-13T00:00:20Z")
        @Volatile var channel: InterpretationChannelDto? = row
        @Volatile var subscription: InterpretationSubscriptionDto? = null
        @Volatile var manager = true
        @Volatile var available = true
        @Volatile var channelStatus = 200
        @Volatile var listenStatus = 200
        @Volatile var renewStatus = 200
        @Volatile var reads = 0
        @Volatile var renews = 0
        val channelBodies = CopyOnWriteArrayList<String>()
        val listenBodies = CopyOnWriteArrayList<String>()
        private val channels = mutableMapOf<String, InterpretationChannelDto>()
        private val subscriptions = mutableMapOf<String, InterpretationSubscriptionDto>()
        private fun fail(status: Int) { if (status != 200) throw HttpException(Response.error<Any>(status, "{}".toResponseBody())) }
        override suspend fun state(roomId: String, sid: String): InterpretationStateDto {
            reads++
            return InterpretationStateDto(available, manager, true, listOf("zh", "en"), listOfNotNull(channel), listOf(TranslationConnectionDto(connection, "PA_me")), listOfNotNull(subscription), 20)
        }
        override suspend fun control(body: RequestBody): InterpretationChannelReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); channelBodies += text
            val request = MeetingInterpretationRepository.channelAdapter.fromJson(text)!!
            val replay = channels.containsKey(text)
            val frozen = channels.getOrPut(text) {
                row.copy(target = request.target, state = if (request.operation == "start") "prepared" else "stopping",
                    archiveRecordId = if (request.saveTranslations == true) UUID.randomUUID().toString() else null).also { channel = it }
            }
            fail(channelStatus); return InterpretationChannelReceiptDto(frozen, replay)
        }
        override suspend fun subscribe(body: RequestBody): InterpretationListenReceiptDto {
            val text = Buffer().also { body.writeTo(it) }.readUtf8(); listenBodies += text
            val request = MeetingInterpretationRepository.listenAdapter.fromJson(text)!!
            val replay = subscriptions.containsKey(text)
            val frozen = subscriptions.getOrPut(text) {
                sub.copy(channelId = request.channelId, revision = request.expectedRevision + 1, active = request.operation == "join",
                    remainingLeaseSeconds = if (request.operation == "join") 20.0 else 0.0).also {
                    subscription = it
                    if (it.active) channel = channel!!.copy(state = "translating")
                }
            }
            fail(listenStatus); return InterpretationListenReceiptDto(frozen, replay)
        }
        override suspend fun renew(body: InterpretationRenewRequestDto): InterpretationSubscriptionDto {
            renews++; fail(renewStatus); return subscription!!
        }
    }
    private inner class Transport : InterpretationTransport {
        override val localSid = "PA_me"
        @Volatile var sourcePresent = true
        @Volatile var latest: List<TranslationAudioGrant> = emptyList()
        override fun current() = true
        override fun activeSources() = if (sourcePresent) setOf("PA_me", "PA_speaker") else setOf("PA_me")
        override fun speakerName(sid: String) = "Speaker"
        override fun packets(): Flow<TranslationPacket> = flow {
            while (true) {
                delay(100)
                val sub = api.subscription ?: continue
                val channel = api.channel ?: continue
                val body = """{"channel_id":"${channel.id}","generation":1,"target":"en","type":"ready","subscription_id":"${sub.id}","subscription_revision":${sub.revision},"source_participation_id":"${api.source}","source_participant_sid":"PA_speaker","audio_track_sid":"TR_audio"}"""
                emit(TranslationPacket(body.toByteArray(), "interpretation-${channel.id}-worker", "PA_agent", true))
            }
        }
        override fun grants(values: List<TranslationAudioGrant>) { latest = values }
    }
}
