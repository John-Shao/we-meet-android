package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OmniCameraToolsTest {
    @Test fun partialOrOldReplyCannotCancelVoiceContinuationWatchdog() = runTest {
        var failures = 0
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, {}, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"reply"}}"""))
        tools.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"reply","delta":"first word"}"""))
        tools.accept(JSONObject("""{"type":"response.audio_transcript.done","response_id":"r1","transcript":"old prelude"}"""))
        advanceTimeBy(15_001); runCurrent(); assertEquals(1, failures); tools.close()
    }
    @Test fun suppressedToolPreludeIsNotRecordedButUserAndContinuationAreRecorded() = runTest {
        val rows = mutableListOf<com.we.meet.feature.assistant.history.AssistantHistoryRow>()
        val transcript = OmniTranscript(rows::add)
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, {}, {}, {}, { result })
        tools.accept(call()); runCurrent()
        val prelude = JSONObject("""{"type":"response.audio_transcript.done","response_id":"r1","item_id":"prelude","transcript":"working"}""")
        assertTrue(tools.suppressesAssistant(prelude))
        transcript.accept(prelude, tools.suppressesAssistant(prelude))
        transcript.accept(JSONObject("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"user","transcript":"camera off"}"""), true)
        val reply = JSONObject("""{"type":"response.audio_transcript.done","response_id":"reply","item_id":"reply","transcript":"camera disabled"}""")
        transcript.accept(reply, tools.suppressesAssistant(reply))
        assertEquals(listOf("user", "assistant"), rows.map { it.role })
        assertEquals("camera disabled", rows.last().text)
        tools.close()
    }
    private fun call(id: String = "c1", args: String = "{\"enabled\":true}", name: String = "set_camera_enabled") =
        JSONObject().put("type", "response.function_call_arguments.done").put("response_id", "r1")
            .put("call_id", id).put("name", name).put("arguments", args)
    private fun done(status: String = "completed", output: org.json.JSONArray = org.json.JSONArray()) =
        JSONObject().put("type", "response.done").put("response", JSONObject()
            .put("id", "r1").put("status", status).put("output", output))
    private val result = CameraActionResult(true, true, true, "enabled", "on")

    @Test fun waitsForBothExecutionAndResponseEndDeduplicatesAndContinuesOnce() = runTest {
        val sent = mutableListOf<JSONObject>(); val held = mutableListOf<Boolean>(); var executed = 0
        val gate = CompletableDeferred<Unit>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; gate.await(); result },
            sent::add, held::add, { fail("Unexpected failure") }, { result.copy(success = false, code = it) })
        tools.accept(call()); tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(1, executed); assertTrue(sent.isEmpty()); assertEquals(true, held.last())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("conversation.item.create", "response.create"), sent.map { it.getString("type") })
        assertEquals("c1", sent.first().getJSONObject("item").getString("call_id"))
        tools.accept(done()); runCurrent(); assertEquals(2, sent.size)
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"reply"}}"""))
        assertEquals(false, held.last()); tools.close()
    }

    @Test fun partialArgumentsNeverExecuteAndFullDoneFallbackUsesSameDeduplication() = runTest {
        var executed = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {}, { result })
        tools.accept(JSONObject("""{"type":"response.function_call_arguments.delta","response_id":"r1","call_id":"c1","delta":"{}"}"""))
        runCurrent(); assertEquals(0, executed)
        val item = call().put("type", "function_call")
        tools.accept(done(output = org.json.JSONArray(listOf(item)))); runCurrent()
        tools.accept(call()); runCurrent()
        assertEquals(1, executed); assertEquals(2, sent.size); tools.close()
    }

    @Test fun strictBooleanUnknownToolAndQueryValidation() = runTest {
        var executed = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {},
            { result.copy(success = false, changed = false, code = it) })
        tools.accept(call("c1", "{\"enabled\":\"false\"}"))
        tools.accept(call("c2", "{}", "launch_arbitrary_tool"))
        tools.accept(call("c3", "{\"unexpected\":1}", "get_camera_state"))
        tools.accept(call("c4", "{}", "get_camera_state"))
        tools.accept(done()); runCurrent()
        assertEquals(1, executed)
        assertEquals(4, sent.count { it.optString("type") == "conversation.item.create" })
        assertEquals(1, sent.count { it.optString("type") == "response.create" }); tools.close()
    }

    @Test fun interruptedOrClosedQueuedCallsCannotTouchHardwareOrContinue() = runTest {
        var executed = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {}, { result })
        tools.accept(call()); tools.interrupted(); tools.accept(done()); runCurrent()
        assertEquals(0, executed); assertTrue(sent.isEmpty())
        tools.accept(call("c2")); tools.close(); runCurrent(); assertEquals(0, executed)
    }

    @Test fun failedResultSendDoesNotRetryHardwareOrCreateResponse() = runTest {
        var executed = 0; var failures = 0
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; result }, { error("Channel closed") }, {},
            { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(1, executed); assertEquals(1, failures); tools.close()
    }

    @Test fun continuationTimeoutReportsOnceWithoutRetry() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertEquals(1, failures); assertEquals(2, sent.size); tools.close()
    }

    @Test fun newSpeechInvalidatesLateToolsFromPreviouslyCreatedResponse() = runTest {
        var executed = 0
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; result }, {}, {}, {}, { result })
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"r1"}}"""))
        tools.accept(JSONObject("""{"type":"input_audio_buffer.speech_started"}"""))
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(0, executed); tools.close()
    }

    @Test fun onlyErrorsAttributedToToolEventsAreRecoverable() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertFalse(tools.recoverableError(JSONObject().put("event_id", "session-config-event")))
        assertTrue(tools.recoverableError(JSONObject().put("event_id", sent.first().getString("event_id"))))
        assertEquals(1, failures); tools.close()
    }

    @Test fun responseCreatedWithoutActualReplyStillTimesOutAndDoesNotRetry() = runTest {
        var failures = 0
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, {}, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"reply"}}"""))
        advanceTimeBy(15_001); runCurrent(); assertEquals(1, failures); tools.close()
    }

    @Test fun originEndAndPermissionWaitDoNotTimeOutFeedback() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>(); val permission = CompletableDeferred<Unit>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { permission.await(); result },
            sent::add, {}, { failures++ }, { result })
        tools.accept(call()); runCurrent(); assertTrue(sent.isEmpty())
        tools.accept(done()); runCurrent(); advanceTimeBy(45_000); runCurrent()
        assertEquals(0, failures); assertTrue(sent.isEmpty())
        permission.complete(Unit); runCurrent()
        assertEquals(listOf("conversation.item.create", "response.create"), sent.map { it.getString("type") })
        tools.close()
    }

    @Test fun aoqCompletedFunctionItemsCanContinueWithoutResponseDoneButNeverBeforeAllResults() = runTest {
        val sent = mutableListOf<JSONObject>(); var executed = 0; val gate = CompletableDeferred<Unit>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { executed++; gate.await(); result },
            sent::add, {}, { fail("Unexpected timeout") }, { result })
        fun item(id: String, index: Int, type: String) = JSONObject().put("type", type).put("response_id", "r1")
            .put("output_index", index).put("item", call(id).put("type", "function_call"))
        tools.accept(item("c1", 0, "response.output_item.added"))
        tools.accept(item("c2", 1, "response.output_item.added"))
        tools.accept(item("c1", 0, "response.output_item.done")); runCurrent(); advanceTimeBy(250); runCurrent()
        assertTrue(sent.isEmpty())
        tools.accept(item("c2", 1, "response.output_item.done")); runCurrent(); advanceTimeBy(250); runCurrent()
        assertEquals(2, executed); assertTrue(sent.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(2, sent.count { it.optString("type") == "conversation.item.create" })
        assertEquals(1, sent.count { it.optString("type") == "response.create" })
        tools.accept(done()); runCurrent(); assertEquals(3, sent.size); tools.close()
    }

    @Test fun lateContinuationCannotUnmuteTimedOutFeedbackBeforeANewUserTurn() = runTest {
        val held = mutableListOf<Boolean>()
        val tools = OmniCameraTools(backgroundScope, CameraToolHandler { result }, {}, held::add, {}, { result })
        tools.accept(call()); tools.accept(done()); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertTrue(held.last())
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"late"}}"""))
        assertTrue(held.last())
        tools.accept(JSONObject("""{"type":"input_audio_buffer.speech_started"}"""))
        assertFalse(held.last()); tools.close()
    }
}
