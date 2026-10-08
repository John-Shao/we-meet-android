package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OmniCallToolsTest {
    @Test fun stateSyncFailureHasNoToolResultAndCannotReuseAnEarlierSuccess() = runTest {
        val failures = mutableListOf<CameraFeedbackFailure>(); val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, failures::add, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.publishState("scene", true)
        assertTrue(tools.recoverableError(JSONObject().put("event_id", sent.last().getString("event_id"))))
        assertEquals(listOf(CameraFeedbackFailure(CameraFeedbackStage.StateSync, null)), failures)
        tools.close()
    }

    @Test fun executionFailureCannotReportTheResultFromAnotherRound() = runTest {
        val failures = mutableListOf<CameraFeedbackFailure>(); var count = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { if (count++ == 0) result else error("device") },
            {}, {}, failures::add, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(call("c2").put("response_id", "r2")); runCurrent()
        assertEquals(listOf(CameraFeedbackFailure(CameraFeedbackStage.ToolExecution, null)), failures)
        tools.close()
    }

    @Test fun resultSendFailureCarriesTheActuallyCompletedCloseWithoutRetrying() = runTest {
        val failures = mutableListOf<CameraFeedbackFailure>(); var operations = 0
        val closedCamera = CameraActionResult(true, false, true, "disabled", "off")
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { operations++; closedCamera },
            { error("send") }, {}, failures::add, { result })
        tools.accept(call(args = "{\"enabled\":false}")); tools.accept(done()); runCurrent()
        assertEquals(listOf(CameraFeedbackFailure(CameraFeedbackStage.ResultSend, closedCamera)), failures)
        assertEquals(1, operations); tools.close()
    }

    @Test fun endTimeoutAndContinuationTimeoutReportTheirOwnResultsOnlyOnce() = runTest {
        for (completed in listOf(false, true)) {
            val failures = mutableListOf<CameraFeedbackFailure>(); val sent = mutableListOf<JSONObject>()
            val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, failures::add, { result })
            tools.accept(call()); if (completed) tools.accept(done()); runCurrent()
            advanceTimeBy(15_001); runCurrent()
            assertEquals(listOf(CameraFeedbackFailure(if (completed) CameraFeedbackStage.Continuation else CameraFeedbackStage.ResponseEnd, result)), failures)
            sent.forEach { tools.recoverableError(JSONObject().put("event_id", it.getString("event_id"))) }
            assertEquals(1, failures.size); tools.close()
        }
    }
    @Test fun rejectedContinuationPreservesCompletedCameraOperationAndAllowsNextCommand() = runTest {
        val sent = mutableListOf<JSONObject>(); var operations = 0; var failures = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { operations++; result }, sent::add, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertTrue(tools.recoverableError(JSONObject().put("event_id", sent.last().getString("event_id"))))
        assertEquals(1, operations); assertEquals(1, failures)
        tools.accept(JSONObject().put("type", "input_audio_buffer.speech_started"))
        tools.accept(call("c2").put("response_id", "r2"))
        tools.accept(done().put("response", JSONObject().put("id", "r2").put("status", "completed")))
        runCurrent()
        assertEquals(2, operations)
        assertEquals(2, sent.count { it.optString("type") == "response.create" })
        tools.close()
    }
    @Test fun webRtcDelayedResponseDoneCannotStartAnOverlappingContinuation() = runTest {
        val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, {}, { result })
        tools.accept(JSONObject().put("type", "response.output_item.done").put("response_id", "r1")
            .put("item", call().put("type", "function_call")))
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("conversation.item.create"), sent.map { it.getString("type") })
        tools.accept(done()); runCurrent()
        assertEquals(1, sent.count { it.optString("type") == "response.create" })
        tools.close()
    }
    @Test fun endingIsTerminalImmediateAndDeduplicatedWithoutResponseEndOrReply() = runTest {
        var ended = 0; val sent = mutableListOf<JSONObject>(); val held = mutableListOf<Boolean>()
        val tools = OmniCallTools(backgroundScope, null, sent::add, held::add, { fail("No farewell watchdog") }, { result }, { ended++ })
        tools.accept(call(args = "{}", name = "end_call")); runCurrent()
        assertEquals(1, ended); assertTrue(held.last()); assertTrue(sent.isEmpty())
        tools.accept(call(args = "{}", name = "end_call")); tools.accept(done()); tools.accept(call("other", "{}", "end_call"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, ended); assertTrue(sent.isEmpty())
    }

    @Test fun everyCompleteToolEventCanEndButPartialArgumentsNeverCan() = runTest {
        for (entry in listOf("arguments", "item", "response")) {
            var ended = 0
            val tools = OmniCallTools(backgroundScope, null, {}, {}, {}, { result }, { ended++ })
            tools.accept(call(args = "{}", name = "end_call").put("type", "response.function_call_arguments.delta"))
            runCurrent(); assertEquals(0, ended)
            val item = call(args = "{}", name = "end_call").put("type", "function_call")
            when (entry) {
                "arguments" -> tools.accept(call(args = "{}", name = "end_call"))
                "item" -> tools.accept(JSONObject().put("type", "response.output_item.done").put("response_id", "r1").put("item", item))
                else -> tools.accept(done(output = org.json.JSONArray(listOf(item))))
            }
            runCurrent(); assertEquals(entry, 1, ended)
        }
    }

    @Test fun malformedOrExtraEndArgumentsAndUnregisteredToolsDoNotHangUp() = runTest {
        var ended = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, null, sent::add, {}, {}, { result.copy(success = false, code = it) }, { ended++ })
        for ((index, args) in listOf("", "null", "[]", "{\"enabled\":true}").withIndex())
            tools.accept(call("bad$index", args, "end_call"))
        tools.accept(call("camera", "{}", "get_camera_state"))
        tools.accept(call("unknown", "{}", "stop_everything")); tools.accept(done()); runCurrent()
        assertEquals(0, ended)
        val outputs = sent.filter { it.optString("type") == "conversation.item.create" }
        assertEquals(6, outputs.size)
        assertTrue(outputs.all { JSONObject(it.getJSONObject("item").getString("output")).getString("code") == "invalid_arguments" })
        tools.close()
    }

    @Test fun interruptedCancelledOrClosedPendingEndCannotHangUp() = runTest {
        for (cancel in listOf("interrupt", "response", "close")) {
            var ended = 0
            val tools = OmniCallTools(backgroundScope, null, {}, {}, {}, { result }, { ended++ })
            tools.accept(call(args = "{}", name = "end_call"))
            when (cancel) {
                "interrupt" -> tools.interrupted()
                "response" -> tools.accept(done(status = "cancelled"))
                else -> tools.close()
            }
            runCurrent(); assertEquals(cancel, 0, ended); tools.close()
        }
    }

    @Test fun endCancelsPendingPermissionOperationAndNeverRequestsItsFeedback() = runTest {
        var ended = 0; var cancelled = false; val permission = CompletableDeferred<Unit>(); val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler {
            try { permission.await(); result } finally { cancelled = true }
        }, sent::add, {}, {}, { result }, { ended++ })
        tools.accept(call()); runCurrent()
        tools.accept(call("stop", "{}", "end_call")); runCurrent()
        assertEquals(1, ended); assertTrue(cancelled)
        permission.complete(Unit); tools.accept(done()); advanceTimeBy(60_000); runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun endInSameResponseCancelsQueuedCameraToolsAndOnlyActsOnce() = runTest {
        var ended = 0; var cameraActions = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { cameraActions++; result }, sent::add, {}, {}, { result }, { ended++ })
        val items = org.json.JSONArray(listOf(call("stop", "{}", "end_call").put("type", "function_call"),
            call("open").put("type", "function_call"), call("stop2", "{}", "end_call").put("type", "function_call")))
        tools.accept(done(output = items)); runCurrent()
        assertEquals(1, ended); assertEquals(0, cameraActions); assertTrue(sent.isEmpty())
    }

    @Test fun hangupRegistrationAndInstructionsAreIndependentOfCameraGate() {
        fun names(camera: Boolean, end: Boolean): List<String> {
            val tools = OmniCallTools.definitions(camera, end)
            return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertEquals(listOf("end_call"), names(false, true))
        assertEquals(listOf("set_camera_enabled", "get_camera_state"), names(true, false))
        assertEquals(3, names(true, true).size); assertTrue(names(false, false).isEmpty())
        val text = OmniCallTools.instructions("scene", false, true)
        assertTrue(text.startsWith("scene")); assertTrue(text.contains("结束对话")); assertTrue(text.contains("停止对话"))
        assertTrue(text.contains("不要结束对话")); assertFalse(text.contains("set_camera_enabled(enabled=true)"))
        assertEquals("scene", OmniCallTools.instructions("scene", false, false))
    }

    @Test fun disabledHangupToolIsRejectedWithoutChangingCameraBehavior() = runTest {
        var cameraActions = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { cameraActions++; result }, sent::add, {}, {}, { result.copy(success = false, code = it) })
        tools.accept(call("stop", "{}", "end_call")); tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(1, cameraActions)
        assertEquals("invalid_arguments", JSONObject(sent.first().getJSONObject("item").getString("output")).getString("code"))
        assertEquals(1, sent.count { it.optString("type") == "response.create" }); tools.close()
    }

    @Test fun partialOrOldReplyCannotCancelVoiceContinuationWatchdog() = runTest {
        var failures = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, {}, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"reply"}}"""))
        tools.accept(JSONObject("""{"type":"response.audio_transcript.delta","response_id":"reply","delta":"first word"}"""))
        tools.accept(JSONObject("""{"type":"response.audio_transcript.done","response_id":"r1","transcript":"old prelude"}"""))
        advanceTimeBy(15_001); runCurrent(); assertEquals(1, failures); tools.close()
    }
    @Test fun suppressedToolPreludeIsNotRecordedButUserAndContinuationAreRecorded() = runTest {
        val rows = mutableListOf<com.we.meet.feature.assistant.history.AssistantHistoryRow>()
        val transcript = OmniTranscript(rows::add)
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, {}, {}, {}, { result })
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
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; gate.await(); result },
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
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {}, { result })
        tools.accept(JSONObject("""{"type":"response.function_call_arguments.delta","response_id":"r1","call_id":"c1","delta":"{}"}"""))
        runCurrent(); assertEquals(0, executed)
        val item = call().put("type", "function_call")
        tools.accept(done(output = org.json.JSONArray(listOf(item)))); runCurrent()
        tools.accept(call()); runCurrent()
        assertEquals(1, executed); assertEquals(2, sent.size); tools.close()
    }

    @Test fun strictBooleanUnknownToolAndQueryValidation() = runTest {
        var executed = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {},
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
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; result }, sent::add, {}, {}, { result })
        tools.accept(call()); tools.interrupted(); tools.accept(done()); runCurrent()
        assertEquals(0, executed); assertTrue(sent.isEmpty())
        tools.accept(call("c2")); tools.close(); runCurrent(); assertEquals(0, executed)
    }

    @Test fun failedResultSendDoesNotRetryHardwareOrCreateResponse() = runTest {
        var executed = 0; var failures = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; result }, { error("Channel closed") }, {},
            { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(1, executed); assertEquals(1, failures); tools.close()
    }

    @Test fun continuationTimeoutReportsOnceWithoutRetry() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertEquals(1, failures); assertEquals(2, sent.size); tools.close()
    }

    @Test fun newSpeechInvalidatesLateToolsFromPreviouslyCreatedResponse() = runTest {
        var executed = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; result }, {}, {}, {}, { result })
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"r1"}}"""))
        tools.accept(JSONObject("""{"type":"input_audio_buffer.speech_started"}"""))
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertEquals(0, executed); tools.close()
    }

    @Test fun onlyErrorsAttributedToToolEventsAreRecoverable() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, sent::add, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        assertFalse(tools.recoverableError(JSONObject().put("event_id", "session-config-event")))
        assertTrue(tools.recoverableError(JSONObject().put("event_id", sent.first().getString("event_id"))))
        assertEquals(1, failures); tools.close()
    }

    @Test fun responseCreatedWithoutActualReplyStillTimesOutAndDoesNotRetry() = runTest {
        var failures = 0
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, {}, {}, { failures++ }, { result })
        tools.accept(call()); tools.accept(done()); runCurrent()
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"reply"}}"""))
        advanceTimeBy(15_001); runCurrent(); assertEquals(1, failures); tools.close()
    }

    @Test fun originEndAndPermissionWaitDoNotTimeOutFeedback() = runTest {
        var failures = 0; val sent = mutableListOf<JSONObject>(); val permission = CompletableDeferred<Unit>()
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { permission.await(); result },
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
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { executed++; gate.await(); result },
            sent::add, {}, { fail("Unexpected timeout") }, { result }, allowMissingResponseDone = true)
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
        val tools = OmniCallTools(backgroundScope, CameraToolHandler { result }, {}, held::add, {}, { result })
        tools.accept(call()); tools.accept(done()); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertTrue(held.last())
        tools.accept(JSONObject("""{"type":"response.created","response":{"id":"late"}}"""))
        assertTrue(held.last())
        tools.accept(JSONObject("""{"type":"input_audio_buffer.speech_started"}"""))
        assertFalse(held.last()); tools.close()
    }
}
