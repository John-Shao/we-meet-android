package com.we.meet.data.capture

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.CaptureTranslationConfigDto
import com.we.meet.data.api.dto.CaptureTranslationRunDto
import com.we.meet.data.api.dto.CaptureTranslationTicketDto
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface CaptureTranslationWire : Closeable {
    val queuedBytes: Long
    fun send(text: String): Boolean
    /** Must synchronously copy input; the caller erases it after returning. */
    fun send(pcm: ByteArray): Boolean
    interface Listener {
        fun opened()
        fun message(text: String)
        fun failed()
    }
}
data class CaptureTranslationText(val id: String, val direction: String, val text: String) {
    override fun toString() = "CaptureTranslationText(<private>)"
}
data class CaptureTranslationLiveState(val phase: String = "connecting", val direction: String? = null,
    val candidate: CaptureTranslationText? = null, val finals: List<CaptureTranslationText> = emptyList()) {
    override fun toString() = "CaptureTranslationLiveState($phase)"
}
private data class CaptureTranslationEvent(
    val type: String,
    @Json(name = "capture_id") val captureId: String,
    @Json(name = "run_id") val runId: String,
    val generation: Long,
    val sequence: Long? = null, val direction: String? = null,
    val configuration: CaptureTranslationConfigDto? = null,
    val status: String? = null, val complete: Boolean? = null,
    @Json(name = "response_id") val responseId: String? = null,
    @Json(name = "item_id") val itemId: String? = null,
    @Json(name = "sample_rate") val sampleRate: Int? = null,
    val audio: String? = null, val text: String? = null,
)

/** One exact foreground recording. No reconnect, source capture, recording mutation or replay. */
class CaptureTranslationSocket(
    private val source: CaptureTranslationSource,
    val run: CaptureTranslationRunDto,
    private val ticket: CaptureTranslationTicketDto,
    private val authorized: () -> Boolean,
    private val observe: () -> CapturePcmTap.Subscription,
    private val connectWire: (String, CaptureTranslationWire.Listener) -> CaptureTranslationWire,
    private val changed: (CaptureTranslationLiveState) -> Unit,
    private val audio: (ShortArray) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val schedule: ((() -> Unit) -> Closeable) = ::scheduleTick,
) : Closeable {
    @Volatile var state = CaptureTranslationLiveState(); private set
    private var wire: CaptureTranslationWire? = null
    private var tap: CapturePcmTap.Subscription? = null
    private var timer: Closeable? = null
    private var ended = false
    private var ready = false
    private var sequence = 0L
    private var deadline = now() + 45000
    private var afterDrain: String? = null
    private var endSequence: Long? = null
    private data class Pending(val audio: Boolean, val since: Long)
    private val pending = linkedMapOf<Long, Pending>()
    private val completed = mutableSetOf<String>()
    private val responses = mutableSetOf<String>()
    private fun permitted() = !ended && runCatching(authorized).getOrDefault(false)
    private fun publish(value: CaptureTranslationLiveState) { state = value; changed(value) }

    @Synchronized fun connect() {
        check(wire == null && permitted())
        CaptureTranslationRepository.validateSource(source)
        CaptureTranslationRepository.validateRun(run, source.capture)
        CaptureTranslationRepository.validateTicket(ticket, source, run, wallClock())
        check(run.status == "starting")
        try {
            wire = connectWire(ticket.gatewayUrl, object : CaptureTranslationWire.Listener {
                override fun opened() = onOpen()
                override fun message(text: String) = receive(text)
                override fun failed() { synchronized(this@CaptureTranslationSocket) { end("unknown") } }
            })
            timer = schedule(::tick)
        } catch (error: Exception) { end("unknown"); throw error }
    }
    @Synchronized private fun onOpen() = guarded {
        CaptureTranslationRepository.validateTicket(ticket, source, run, wallClock())
        check(requireNotNull(wire).send(json.toJson(mapOf("type" to "authenticate", "ticket" to ticket.ticket,
            "run_id" to run.id, "capture_id" to source.capture, "generation" to run.generation))))
    }
    @Synchronized private fun receive(raw: String) = guarded {
        require(raw.length in 2..131072)
        val event = requireNotNull(events.fromJson(raw))
        require(event.captureId == source.capture && event.runId == run.id && event.generation == run.generation)
        if (event.type == "finished") {
            require(event.status in terminal && event.complete != null && event.complete == (event.status == "stopped"))
            end(event.status!!); return@guarded
        }
        if (event.type == "ready") {
            require(!ready && event.configuration == run.configuration)
            ready = true; deadline = 0
            publish(state.copy(phase = "ready"))
            if (run.configuration.mode == "simultaneous") tap = observe()
            return@guarded
        }
        require(ready)
        if (event.type == "ack") {
            require(event.sequence != null && event.sequence == pending.keys.firstOrNull())
            pending.remove(event.sequence); return@guarded
        }
        require(event.direction == "forward" || event.direction == "reverse" && run.configuration.mode == "push_to_talk")
        val direction = event.direction!!
        if (event.type == "turn_empty") {
            require(state.phase in setOf("awaiting", "finishing") && state.direction == direction && event.sequence == endSequence)
            if (state.phase != "finishing") { deadline = 0; publish(state.copy(phase = "ready", direction = null)) }
            return@guarded
        }
        id(event.responseId)
        if (event.type == "response_completed") {
            val responseKey = json.toJson(listOf(direction, event.responseId))
            if (responseKey in responses) return@guarded
            require(responses.size < 20000); responses.add(responseKey)
            if (state.phase == "awaiting" && state.direction == direction) { deadline = 0; publish(state.copy(phase = "ready", direction = null)) }
            return@guarded
        }
        id(event.itemId)
        if (event.type == "audio") {
            require(run.configuration.audio && event.sampleRate == 24000 && event.audio?.length in 1..64000)
            val bytes = Base64.getDecoder().decode(event.audio!!)
            try {
                require(bytes.size in 2..48000 && bytes.size % 2 == 0)
                val samples = ShortArray(bytes.size / 2)
                try { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples); audio(samples) }
                finally { samples.fill(0) }
            } finally { bytes.fill(0) }
            return@guarded
        }
        require(event.type in setOf("target_candidate", "target_final") && event.text != null && event.text.length <= 20000)
        val row = CaptureTranslationText(json.toJson(listOf(direction, event.responseId, event.itemId)), direction, event.text)
        require(row.id !in completed)
        if (event.type == "target_candidate") publish(state.copy(candidate = row))
        else {
            require(row.text.isNotBlank() && completed.size < 20000)
            completed.add(row.id)
            val rows = (state.finals + row).takeLast(30).toMutableList()
            while (rows.size > 1 && rows.sumOf { it.text.length } > 100000) rows.removeAt(0)
            publish(state.copy(finals = rows, candidate = state.candidate?.takeUnless { it.id == row.id }))
        }
    }
    private fun send(text: String, pcm: ByteArray? = null) {
        check(permitted() && ready && sequence <= 0xffffffffL && requireNotNull(wire).queuedBytes <= 64000 && pending.size < 8)
        check(pcm == null || pending.values.count { it.audio } < 4)
        pending[sequence] = Pending(pcm != null, now())
        check(if (pcm != null) wire!!.send(pcm) else wire!!.send(text))
    }
    private fun control(type: String, direction: String? = null) {
        send(json.toJson(mapOf("type" to type, "sequence" to ++sequence) + if (direction == null) emptyMap() else mapOf("direction" to direction)))
    }
    @Synchronized private fun tick() = guarded {
        if ((deadline != 0L && now() > deadline) || pending.values.any { now() - it.since > 5000 }) { end("unknown"); return@guarded }
        val input = tap ?: return@guarded
        require(input.state in setOf(CapturePcmTap.State.RUNNING, CapturePcmTap.State.FINISHED))
        repeat(4) {
            if (pending.values.count { it.audio } >= 4) return@repeat
            val frame = input.poll()
            if (frame == null) {
                if (input.state == CapturePcmTap.State.FINISHED) drained()
                return@guarded
            }
            frame.use {
                require(it.samples.size in 16..1600 && it.samples.size % 16 == 0)
                val bytes = ByteArray(4 + it.samples.size * 2)
                try {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { putInt((++sequence).toInt()); asShortBuffer().put(it.samples) }
                    send("", bytes)
                } finally { bytes.fill(0) }
            }
        }
    }
    @Synchronized fun begin(direction: String) {
        if (state.phase != "ready" || run.configuration.mode != "push_to_talk") return
        guarded { require(direction in setOf("forward", "reverse")); publish(state.copy(phase = "speaking", direction = direction)); control("begin", direction); tap = observe() }
    }
    @Synchronized fun endTurn() {
        if (state.phase != "speaking") return
        guarded { afterDrain = "end"; deadline = now() + 2000; publish(state.copy(phase = "draining")); requireNotNull(tap).finish() }
    }
    @Synchronized fun finish() {
        if (!ready || ended || state.phase == "finishing") return
        guarded {
            afterDrain = "finish"; deadline = now() + 45000; publish(state.copy(phase = "finishing"))
            if (tap != null) tap!!.finish() else control("finish")
        }
    }
    private fun drained() {
        tap?.close(); tap = null
        val finishing = afterDrain == "finish"
        require(afterDrain != null); afterDrain = null; deadline = now() + 45000
        if (run.configuration.mode == "push_to_talk" && state.direction != null) {
            endSequence = sequence + 1
            if (!finishing) publish(state.copy(phase = "awaiting"))
            control("end", state.direction)
        }
        if (finishing) control("finish")
    }
    private fun guarded(action: () -> Unit) {
        if (ended) return
        try { check(permitted()); action() }
        catch (_: Exception) { end("incomplete") }
    }
    private fun end(phase: String) {
        if (ended) return
        ended = true
        timer?.close(); timer = null
        tap?.close(); tap = null
        runCatching { wire?.close() }
        pending.clear(); completed.clear(); responses.clear()
        publish(state.copy(phase = phase, candidate = null, direction = null, finals = if (phase == "stopped") state.finals else emptyList()))
    }
    @Synchronized override fun close() { end("incomplete") }
    override fun toString() = "CaptureTranslationSocket(<private>)"

    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private val events = moshi.adapter(CaptureTranslationEvent::class.java)
        private val json = moshi.adapter(Any::class.java)
        private val terminal = setOf("stopped", "incomplete", "unknown")
        private fun id(value: String?) { require(value?.length in 1..128) }
        private fun scheduleTick(action: () -> Unit): Closeable {
            val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "capture-translation-pcm").apply { isDaemon = true } }
            executor.scheduleWithFixedDelay(action, 10, 10, TimeUnit.MILLISECONDS)
            return Closeable { executor.shutdownNow() }
        }
    }
}
