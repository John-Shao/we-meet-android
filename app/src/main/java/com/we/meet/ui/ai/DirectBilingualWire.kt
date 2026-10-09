package com.we.meet.ui.ai

import android.content.Context
import android.util.Base64
import com.we.meet.data.api.*
import com.we.meet.data.capture.CaptureTranslationWire
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream

/** Local routing and bounded buffering; every speech frame goes directly to Aliyun. */
internal class DirectBilingualWire(
    private val context: Context, private val api: AssistantTranslationApi,
    private val pair: AssistantTranslationPair, private val listener: CaptureTranslationWire.Listener,
    fixedSource: String? = null,
    private val webRtc: Boolean = false,
) : CaptureTranslationWire {
    private val plan = BilingualSessionPlan(pair, fixedSource)
    private val leases = mutableListOf<com.we.meet.feature.assistant.aicall.data.DirectAILease>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun connection(detection: Boolean, reverse: Boolean = false): BilingualModelConnection =
        if (webRtc) WebRtcBilingualConnection(context.applicationContext, detection)
        else AoqBilingualConnection(context.applicationContext, detection, reverse)
    private val translator = connection(false)
    private val reverse = connection(false, true)
    private val detector = connection(true)
    private val frames = Channel<ByteArray>(100)
    private val boundaries = Channel<Unit>(Channel.CONFLATED)
    private val updates = mapOf(translator to Channel<Unit>(Channel.CONFLATED), reverse to Channel<Unit>(Channel.CONFLATED))
    private var responseId = ""
    private var sourceText = ""
    private var sourceCompleted = false
    private val pendingPcm = ByteArrayOutputStream()
    private var translated = ""
    private var row = 0
    private var lastPcmAt = 0L
    private var responding = false
    private var ending: Job? = null
    @Volatile private var closed = false
    @Volatile private var finishing = false
    private val queued = java.util.concurrent.atomic.AtomicLong()
    override val queuedBytes get() = queued.get()

    init {
        scope.launch {
            try { run() }
            catch (e: CancellationException) { if (e is TimeoutCancellationException && !closed) listener.failed() }
            catch (e: Exception) {
                val frame = e.stackTrace.firstOrNull()
                android.util.Log.w("DirectBilingual", "failure_class=${e.javaClass.simpleName} at=${frame?.className}:${frame?.lineNumber}")
                if (!closed) listener.failed()
            }
        }
    }

    private suspend fun run() = coroutineScope {
        val purposes = if (plan.automatic) listOf("translation", "reverse", "language_detection") else listOf("translation")
        val connected = if (plan.automatic) listOf(translator, reverse, detector) else listOf(translator)
        connected.mapIndexed { index, connection -> async {
            val purpose = purposes[index]
            connection.connect { offer -> api.directSession(AssistantTranslationDirectRequest(
                if (purpose == "reverse") pair.target else plan.inputLanguage,
                if (purpose == "reverse") pair.source else plan.outputLanguage,
                if (purpose == "reverse") "translation" else purpose,
                if (webRtc) "webrtc" else "aoq", offer)).also {
                it.sessionLease?.let { info ->
                    val lease = com.we.meet.feature.assistant.aicall.data.DirectAILease(info, api::sessionLease,
                        { scope.launch { if (!closed) listener.failed() } })
                    if (closed) lease.close() else { leases += lease; lease.start() }
                }
                check(!closed)
                check(it.model == if (purpose == "language_detection") "qwen3.8-omni-flash-realtime" else "qwen3.8-livetranslate-flash-realtime")
            } }
        } }.awaitAll()
        launch { for (event in translator.events) accept(event, translator, plan.inputLanguage) }
        if (plan.automatic) launch { for (event in reverse.events) accept(event, reverse, pair.target) }
        val translations = if (plan.automatic) listOf(translator to pair.target, reverse to pair.source)
            else listOf(translator to plan.outputLanguage)
        translations.map { (connection, language) -> async { configureTranslation(connection, language) } }.awaitAll()
        if (plan.automatic) {
        detector.send(JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("modalities", JSONArray(listOf("text"))).put("turn_detection", JSONObject.NULL)
            .put("audio", JSONObject().put("input", JSONObject().put("format", JSONObject().put("type", "pcm").put("sample_rate", 16000))))
            .put("instructions", "判断本次音频主要使用的语言，只输出 ${pair.source} 或 ${pair.target}。无法确定或没有有效语音时输出 unknown。不要翻译或回复音频内容。短词、问候和简短回答也是有效语音。"))) // i18n-exempt: Model classification prompt, independent of UI locale.
        withTimeout(25_000) {
            while (true) {
                val event = detector.events.receive()
                if (event.optString("type") == "error") {
                    android.util.Log.w("DirectBilingual", "language_setup_code=${event.optJSONObject("error")?.optString("code")}")
                    error("Language setup failed")
                }
                if (event.optString("type") == "session.updated") break
            }
        }
        }
        emit(JSONObject().put("type", "ready"))
        android.util.Log.i("DirectBilingual", "direct_ready model_connections=${plan.connections}")
        val buffer = ByteArrayOutputStream()
        var direction: String? = plan.source
        var probe: Deferred<String?>? = null
        var nextProbe = 25600
        while (isActive) {
            select<Unit> {
                frames.onReceiveCatching { result ->
                    val bytes = result.getOrNull()
                    if (bytes == null) {
                        if (direction == null && buffer.size() > 0) {
                            val language = probe?.await() ?: classify(buffer.toByteArray())
                            probe = null
                            if (language != null) {
                                direction = language
                                append(connection(language), buffer.toByteArray())
                            } else emit(JSONObject().put("type", "language_unknown"))
                        }
                        // Flush the provider's current VAD turn before closing it.
                        if (direction != null) append(connection(direction!!), ByteArray(38400))
                        launch {
                            delay(1500)
                            withTimeout(20_000) { while (responding) delay(50) }
                            translations.forEach { (connection, _) -> connection.send(JSONObject().put("type", "session.finish")) }
                        }
                        return@onReceiveCatching
                    }
                    queued.addAndGet(-bytes.size.toLong())
                    emit(JSONObject().put("type", "ack"))
                    if (direction != null) append(connection(direction!!), bytes)
                    else if (buffer.size() > 0 || voiced(bytes)) {
                        check(buffer.size() + bytes.size <= 320000)
                        buffer.write(bytes)
                        if (probe == null && buffer.size() >= nextProbe) {
                            val sample = buffer.toByteArray()
                            probe = async { classify(sample) }
                        }
                    }
                }
                boundaries.onReceive {
                    direction = plan.source; buffer.reset(); nextProbe = 25600
                }
                probe?.let { current ->
                    current.onAwait { language ->
                        android.util.Log.i("DirectBilingual", "language_result=${language ?: "unknown"}")
                        probe = null
                        if (language == null) {
                            nextProbe = buffer.size() + 12800
                            if (buffer.size() >= 320000) { buffer.reset(); nextProbe = 25600; emit(JSONObject().put("type", "language_unknown")) }
                        } else {
                            direction = language
                            append(connection(language), buffer.toByteArray()); buffer.reset()
                        }
                    }
                }
            }
            if (finishing && frames.isClosedForReceive) { awaitCancellation() }
        }
    }

    private fun voiced(bytes: ByteArray): Boolean {
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0; var count = 0
        while (pcm.remaining() >= 2) { val sample = pcm.short.toDouble(); sum += sample * sample; count++ }
        return count > 0 && sum / count > 200.0 * 200.0
    }
    private suspend fun append(connection: BilingualModelConnection, bytes: ByteArray) {
        for (offset in bytes.indices step 16000) connection.send(JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(bytes.copyOfRange(offset, minOf(offset + 16000, bytes.size)), Base64.NO_WRAP)))
    }
    private suspend fun classify(bytes: ByteArray): String? = withTimeout(5000) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        detector.send(JSONObject().put("type", "input_audio_buffer.clear"))
        append(detector, bytes)
        detector.send(JSONObject().put("type", "input_audio_buffer.commit"))
        detector.send(JSONObject().put("type", "response.create"))
        var text = ""
        val items = mutableSetOf<String>()
        while (true) {
            val event = detector.events.receive()
            when (event.optString("type")) {
                "conversation.item.created" -> event.optJSONObject("item")?.optString("id")?.takeIf { it.isNotEmpty() }?.let { items.add(it) }
                "response.text.delta" -> text += event.optString("delta")
                "response.text.done" -> text = event.optString("text")
                "error" -> {
                    android.util.Log.w("DirectBilingual", "language_code=${event.optJSONObject("error")?.optString("code")}")
                    error("Language detection failed")
                }
                "response.done" -> {
                    check(event.optJSONObject("response")?.optString("status") == "completed")
                    // Keep each classification independent of previous conversation.
                    for (id in items) detector.send(JSONObject().put("type", "conversation.item.delete").put("item_id", id))
                    android.util.Log.i("DirectBilingual", "language_probe input_ms=${bytes.size / 32} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                    return@withTimeout text.trim().lowercase().takeIf { it == pair.source || it == pair.target }
                }
            }
            check(text.length <= 64 && items.size <= 8)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    private fun connection(language: String) = if (!plan.automatic || language == pair.source) translator else reverse
    private suspend fun configureTranslation(connection: BilingualModelConnection, language: String) {
        connection.send(JSONObject().put("type", "session.update").put("session", JSONObject()
            .put("output_modalities", JSONArray(listOf("text", "audio")))
            .put("audio", JSONObject()
                .put("input", JSONObject().put("format", JSONObject().put("type", "pcm").put("sample_rate", 16000))
                    .put("turn_detection", JSONObject().put("type", "server_vad").put("threshold", 0.2).put("silence_duration_ms", 1000)))
                .put("output", JSONObject().put("format", JSONObject().put("type", "pcm").put("sample_rate", 24000)).put("voice", "Tina")))
            .put("translation", JSONObject().put("language", language))))
        withTimeout(25_000) { updates.getValue(connection).receive() }
    }
    private var finishedConnections = 0
    private fun accept(event: JSONObject, connection: BilingualModelConnection, language: String) {
        val kind = event.optString("type")
        if (kind in setOf("session.updated", "response.created", "response.done", "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped", "conversation.item.input_audio_transcription.completed", "response.audio.done"))
            android.util.Log.i("DirectBilingual", "translation_event=$kind")
        when (event.optString("type")) {
            "session.updated" -> updates.getValue(connection).trySend(Unit)
            "response.created" -> {
                responding = true
                responseId = event.getJSONObject("response").getString("id"); translated = ""; sourceCompleted = false; row++
            }
            "conversation.item.input_audio_transcription.delta" -> sourceText += event.optString("delta")
            "conversation.item.input_audio_transcription.completed" -> {
                sourceText = event.optString("transcript", event.optString("text"))
                sourceCompleted = true
                val buffered = pendingPcm.toByteArray(); pendingPcm.reset()
                for (offset in buffered.indices step 24000) emitAudio(Base64.encodeToString(
                    buffered.copyOfRange(offset, minOf(offset + 24000, buffered.size)), Base64.NO_WRAP))
            }
            "response.audio_transcript.delta", "response.text.delta" -> translated += event.optString("delta")
            "response.audio_transcript.done", "response.text.done", "response.audio_transcript.text", "response.text.text" -> translated = event.optString("transcript", event.optString("text", translated))
            "response.audio.delta" -> emit(JSONObject().put("type", "audio").put("id", row.toString()).put("audio", event.getString("delta")))
            "aoq.audio", "rtc.audio" -> if (responseId.isNotEmpty()) {
                if (!event.optBoolean("silent")) lastPcmAt = android.os.SystemClock.elapsedRealtime()
                val encoded = event.getString("audio")
                if (sourceCompleted) emitAudio(encoded)
                else {
                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                    check(pendingPcm.size() + bytes.size <= 60 * 48000)
                    pendingPcm.write(bytes)
                }
            }
            "response.done" -> {
                val response = event.getJSONObject("response")
                check(response.optString("status") == "completed")
                val canonical = StringBuilder()
                response.optJSONArray("output")?.let { output ->
                    check(output.length() <= 8)
                    for (i in 0 until output.length()) output.getJSONObject(i).optJSONArray("content")?.let { parts ->
                        check(parts.length() <= 8)
                        for (j in 0 until parts.length()) {
                            val part = parts.getJSONObject(j)
                            canonical.append(part.optString("transcript", part.optString("text")))
                            check(canonical.length <= 20000)
                        }
                    }
                }
                if (canonical.isNotBlank()) translated = canonical.toString()
                if (translated.isNotBlank()) emit(JSONObject().put("type", "translation").put("id", row.toString())
                    .put("source", sourceText).put("text", translated).put("source_language", language).put("target_language", BilingualLanguages.opposite(pair, language)))
                val id = row.toString()
                ending?.cancel()
                // AOQ's response.done precedes the native decoded playback tail.
                // End the app queue only after decoding has gone idle; otherwise
                // late PCM is rejected by both playback and replay caches.
                ending = scope.launch {
                    delay(350)
                    withTimeout(20_000) {
                        while (android.os.SystemClock.elapsedRealtime() - lastPcmAt < 350) delay(50)
                    }
                    emit(JSONObject().put("type", "audio_end").put("id", id))
                    responseId = ""; responding = false; boundaries.trySend(Unit)
                }
                sourceText = ""; translated = ""
            }
            "session.finished" -> { finishedConnections++; if (finishedConnections == if (plan.automatic) 2 else 1) emit(JSONObject().put("type", "finished")) }
            "error" -> {
                android.util.Log.w("DirectBilingual", "translation_code=${event.optJSONObject("error")?.optString("code")}")
                error("Translation failed")
            }
        }
        check(sourceText.length <= 20000 && translated.length <= 20000)
    }
    private fun emitAudio(encoded: String) = emit(JSONObject().put("type", "audio").put("id", row.toString()).put("audio", encoded))
    private fun emit(event: JSONObject) { if (!closed) listener.message(event.toString()) }
    override fun send(text: String): Boolean {
        if (closed) return false
        if (JSONObject(text).optString("type") == "finish") { finishing = true; frames.close() }
        return true
    }
    override fun send(pcm: ByteArray): Boolean {
        if (closed || finishing) return false
        queued.addAndGet(pcm.size.toLong())
        if (!frames.trySend(pcm.copyOf()).isSuccess) { queued.addAndGet(-pcm.size.toLong()); return false }
        return true
    }
    override fun close() {
        if (closed) return
        closed = true; scope.cancel(); translator.close(); reverse.close(); detector.close(); frames.cancel()
        leases.forEach { it.close() }; leases.clear()
        boundaries.cancel(); updates.values.forEach { it.cancel() }
        pendingPcm.reset()
    }
}
