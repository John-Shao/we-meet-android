package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Main-thread, per-connection Function Calling state. Never executes partial arguments. */
internal class OmniCallTools(
    private val scope: CoroutineScope,
    private val handler: CameraToolHandler?,
    private val send: (JSONObject) -> Unit,
    private val holdOutput: (Boolean) -> Unit,
    private val feedbackFailed: (CameraFeedbackFailure) -> Unit,
    private val invalid: (String) -> CameraActionResult,
    private val endCall: (() -> Unit)? = null,
    private val allowMissingResponseDone: Boolean = false,
) {
    private sealed interface Request {
        data class Camera(val request: CameraToolRequest) : Request
        data object EndCall : Request
    }
    fun definitions(): JSONArray = Companion.definitions(handler != null, endCall != null, serverInstructions)
    private var serverInstructions: Map<String, String> = emptyMap()
    fun configureInstructions(value: Map<String, String>) {
        if (handler != null) {
            for (key in listOf("camera", "camera_state", "set_camera_enabled_description", "get_camera_state_description")) require(!value[key].isNullOrBlank())
        }
        if (endCall != null) {
            require(!value["end_call"].isNullOrBlank())
            require(!value["end_call_description"].isNullOrBlank())
        }
        serverInstructions = value
    }
    fun instructions(base: String): String = Companion.instructions(base, handler != null, endCall != null, serverInstructions)
    private class Round {
        val calls = linkedMapOf<String, Job?>()
        var done = false
        var cancelled = false
        var continued = false
        var feedbackFailed = false
        var outstanding = 0
        var hasTools = false
        var endWatchdog: Job? = null
        val items = mutableSetOf<Int>()
        val completedItems = mutableSetOf<Int>()
        var hasMessage = false
        var functionOutputComplete = false
        var settle: Job? = null
        var continuationId: String? = null
        var result: CameraActionResult? = null
    }
    private val rounds = linkedMapOf<String, Round>()
    private val seen = mutableSetOf<String>()
    private data class SentEvent(val round: Round, val type: String)
    private val sentEvents = linkedMapOf<String, SentEvent>()
    private var waiting: Round? = null
    private var awaitingPlayback: Round? = null
    private var feedbackTimedOut = false
    private var watchdog: Job? = null
    private var closed = false

    fun accept(event: JSONObject) {
        if (closed) return
        when (event.optString("type")) {
            "input_audio_buffer.speech_started" -> { feedbackTimedOut = false; interrupted() }
            "response.created" -> {
                val id = event.optJSONObject("response")?.optString("id").orEmpty()
                if (id.isNotBlank()) rounds.getOrPut(id, ::Round)
                if (waiting != null) { waiting?.continuationId = id; awaitingPlayback = waiting; waiting = null }
                refreshHold()
            }
            "response.audio_transcript.done", "response.text.done" -> {
                if (awaitingPlayback?.continuationId == event.optString("response_id")) playback()
            }
            "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return
                val id = event.optString("response_id")
                if (id.isNotBlank()) {
                    val round = rounds.getOrPut(id, ::Round)
                    round.settle?.cancel(); round.functionOutputComplete = false
                    if (item.optString("type") == "function_call") {
                        round.hasTools = true; round.items += event.optInt("output_index", 0)
                    } else round.hasMessage = true
                }
                refreshHold()
            }
            "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return
                val id = event.optString("response_id")
                if (id.isBlank() || item.optString("type") != "function_call") return
                val index = event.optInt("output_index", 0)
                call(id, JSONObject(item.toString()).put("output_index", index))
                if (closed) return
                val round = rounds.getOrPut(id, ::Round)
                round.items += index; round.completedItems += index
                round.settle?.cancel()
                // FC returns no audio. AOQ may omit response.done for such rounds.
                // Drain adjacent completed tool items before the one continuation.
                if (allowMissingResponseDone && !round.hasMessage && round.completedItems.containsAll(round.items)) round.settle = scope.launch {
                    delay(200)
                    if (!closed && !round.cancelled) {
                        round.functionOutputComplete = true
                        round.endWatchdog?.cancel()
                        continueRound(round); refreshHold()
                    }
                }
            }
            "response.function_call_arguments.done" -> call(event.optString("response_id"), event)
            "response.done" -> {
                val response = event.optJSONObject("response") ?: return
                val id = response.optString("id")
                if (id.isBlank()) return
                val round = rounds.getOrPut(id, ::Round)
                if (response.optString("status", "completed") != "completed") {
                    cancel(round)
                } else {
                    val output = response.optJSONArray("output") ?: JSONArray()
                    for (i in 0 until output.length()) {
                        if (closed) return
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") == "function_call") call(id, item)
                    }
                    if (closed) return
                    round.done = true
                    round.endWatchdog?.cancel()
                    round.settle?.cancel()
                    continueRound(round)
                }
                refreshHold()
            }
        }
    }

    private fun call(responseId: String, item: JSONObject) {
        if (closed) return
        val callId = item.optString("call_id")
        if (responseId.isBlank() || callId.isBlank() || !seen.add(callId)) return
        val round = rounds.getOrPut(responseId, ::Round)
        if (round.cancelled) return
        round.hasTools = true
        round.items += item.optInt("output_index", 0)
        val request = runCatching {
            val args = JSONObject(item.getString("arguments"))
            when (item.getString("name")) {
                "set_camera_enabled" -> {
                    require(handler != null)
                    require(args.length() == 1 && args.get("enabled") is Boolean)
                    Request.Camera(CameraToolRequest.SetEnabled(args.getBoolean("enabled")))
                }
                "get_camera_state" -> { require(handler != null && args.length() == 0); Request.Camera(CameraToolRequest.GetState) }
                "end_call" -> { require(endCall != null && args.length() == 0); Request.EndCall }
                else -> error("Unknown tool")
            }
        }.getOrNull()
        round.outstanding++
        round.calls[callId] = null
        refreshHold()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (closed || round.cancelled) return@launch
                if (request == Request.EndCall) {
                    // Terminal action: cancel pending tools/feedback before invoking the
                    // owner's existing hangup path. No output or continuation on a closed call.
                    holdOutput(true)
                    close()
                    checkNotNull(endCall).invoke()
                    return@launch
                }
                // CameraActionController serializes hardware. Let OFF enter the
                // delegate while OPEN awaits permission so it can cancel that wait.
                val result = if (request == null) invalid("invalid_arguments")
                    else checkNotNull(handler).execute((request as Request.Camera).request)
                round.result = result
                if (!closed) {
                    post(round, JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "function_call_output").put("call_id", callId).put("output", result.json())))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failFeedback(round, if (round.result == null) CameraFeedbackStage.ToolExecution else CameraFeedbackStage.ResultSend)
            }
        }
        round.calls[callId] = job
        job.invokeOnCompletion {
            round.outstanding--
            continueRound(round)
            refreshHold()
            awaitOutputEnd(round)
        }
        job.start()
        awaitOutputEnd(round)
    }

    private fun awaitOutputEnd(round: Round) {
        if (!closed && !round.cancelled && round.outstanding == 0 && !round.done &&
            !round.functionOutputComplete && round.endWatchdog == null) {
            round.endWatchdog = scope.launch {
                delay(15_000)
                if (!round.done && !round.functionOutputComplete && !round.cancelled) {
                    failFeedback(round, CameraFeedbackStage.ResponseEnd); cancel(round); refreshHold()
                }
            }
        }
    }

    private fun continueRound(round: Round) {
        if (closed || (!round.done && !round.functionOutputComplete) || round.cancelled || round.continued || round.feedbackFailed ||
            round.outstanding != 0 || round.calls.isEmpty()) return
        round.continued = true
        waiting = round
        try {
            post(round, JSONObject().put("type", "response.create"))
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(15_000)
                if (waiting === round || awaitingPlayback === round) {
                    failFeedback(round, CameraFeedbackStage.Continuation)
                    waiting = null
                    awaitingPlayback = null
                    feedbackTimedOut = true
                    refreshHold()
                }
            }
        } catch (_: Exception) {
            waiting = null; failFeedback(round, CameraFeedbackStage.Continuation)
        }
    }

    private fun refreshHold() {
        if (!closed) holdOutput(feedbackTimedOut || waiting != null || rounds.values.any {
            !it.cancelled && !it.feedbackFailed && !it.continued && (!it.done || it.outstanding > 0) &&
                it.hasTools
        })
    }

    private fun cancel(round: Round) {
        round.cancelled = true
        round.endWatchdog?.cancel()
        round.settle?.cancel()
        round.calls.values.forEach { it?.cancel() }
        if (waiting === round) { waiting = null; watchdog?.cancel() }
        if (awaitingPlayback === round) { awaitingPlayback = null; watchdog?.cancel() }
    }

    fun interrupted() {
        rounds.values.filter { !it.continued || it === waiting || it === awaitingPlayback }.forEach(::cancel)
        refreshHold()
    }

    fun playback() {
        if (awaitingPlayback != null) { awaitingPlayback = null; watchdog?.cancel() }
    }

    fun suppressesAssistant(event: JSONObject): Boolean = feedbackTimedOut ||
        rounds[event.optString("response_id")]?.hasTools == true

    fun publishState(base: String, enabled: Boolean?) {
        if (closed || handler == null) return
        val snapshot = "\n" + serverInstructions["camera_state"].orEmpty().replace("{camera_state}", enabled?.toString() ?: "unknown")
        val round = Round()
        try {
            post(round, JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("instructions", instructions(base) + snapshot)))
        } catch (_: Exception) { failFeedback(round, CameraFeedbackStage.StateSync) }
    }

    private fun post(round: Round, event: JSONObject) {
        val id = UUID.randomUUID().toString()
        event.put("event_id", id)
        sentEvents[id] = SentEvent(round, event.getString("type"))
        while (sentEvents.size > 256) sentEvents.remove(sentEvents.keys.first())
        send(event)
    }

    fun recoverableError(error: JSONObject): Boolean {
        val sent = sentEvents.remove(error.optString("event_id")) ?: return false
        val round = sent.round
        if (waiting === round) { waiting = null; watchdog?.cancel() }
        if (awaitingPlayback === round) { awaitingPlayback = null; watchdog?.cancel() }
        failFeedback(round, when (sent.type) {
            "session.update" -> CameraFeedbackStage.StateSync
            "conversation.item.create" -> CameraFeedbackStage.ResultSend
            else -> CameraFeedbackStage.Continuation
        })
        refreshHold()
        return true
    }

    private fun failFeedback(round: Round, stage: CameraFeedbackStage) {
        if (closed || round.cancelled || round.feedbackFailed) return
        round.feedbackFailed = true
        feedbackFailed(CameraFeedbackFailure(stage, round.result))
    }

    fun close() {
        closed = true; watchdog?.cancel()
        rounds.values.forEach(::cancel)
        rounds.clear(); seen.clear(); sentEvents.clear(); waiting = null; awaitingPlayback = null
    }

    companion object {
        fun definitions(cameraEnabled: Boolean = true, endCallEnabled: Boolean = false, rules: Map<String, String> = emptyMap()): JSONArray {
            val result = JSONArray()
            if (cameraEnabled) listOf(
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", "set_camera_enabled")
                .put("description", rules["set_camera_enabled_description"].orEmpty())
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("enabled", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray(listOf("enabled"))).put("additionalProperties", false))),
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", "get_camera_state").put("description", rules["get_camera_state_description"].orEmpty())
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())
                    .put("additionalProperties", false))),
            ).forEach(result::put)
            if (endCallEnabled) result.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", "end_call")
                .put("description", rules["end_call_description"].orEmpty())
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())
                    .put("additionalProperties", false))))
            return result
        }

        fun instructions(base: String, cameraEnabled: Boolean = true, endCallEnabled: Boolean = false,
            rules: Map<String, String> = emptyMap()): String =
            base + (if (cameraEnabled) rules["camera"]?.let { "\n$it" }.orEmpty() else "") +
                (if (endCallEnabled) rules["end_call"]?.let { "\n$it" }.orEmpty() else "")
    }
}
