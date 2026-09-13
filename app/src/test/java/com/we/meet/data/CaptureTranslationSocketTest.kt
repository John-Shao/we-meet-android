package com.we.meet.data

import com.squareup.moshi.Moshi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CaptureTranslationSocketTest {
    private class Wire : CaptureTranslationWire {
        override var queuedBytes = 0L
        val messages = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        val erased = mutableListOf<ByteArray>()
        var closes = 0
        override fun send(text: String): Boolean { messages += text; return true }
        override fun send(pcm: ByteArray): Boolean { erased += pcm; frames += pcm.copyOf(); return true }
        override fun close() { closes++ }
    }
    private class Fixture(manual: Boolean = false, sound: Boolean = false) : Closeable {
        private fun id() = UUID.randomUUID().toString()
        private val json = Moshi.Builder().build().adapter(Any::class.java)
        val source = CaptureTranslationSource(id(), id(), id(), "android", id())
        val config = CaptureTranslationConfigDto("zh", "en", if (manual) "push_to_talk" else "simultaneous", sound, false, CaptureTranslationRepository.MODEL, "cn-beijing")
        val run = CaptureTranslationRunDto(id(), source.capture, 1, 2, config, "starting", OffsetDateTime.now().plusSeconds(30).toString(), null, "")
        val ticket = CaptureTranslationTicketDto("private-ticket", "wss://fixture.invalid/capture-translation", run.deadline, CaptureTranslationTicketSourceDto(run.id, source.capture, source.viewer, source.device, 1, 2))
        var authorized = true
        var now = 1000L
        var ticks: () -> Unit = {}
        var timerClosed = false
        var attaches = 0
        var connections = 0
        lateinit var listener: CaptureTranslationWire.Listener
        val wire = Wire()
        val tap = CapturePcmTap { authorized }
        val played = mutableListOf<ShortArray>()
        val erased = mutableListOf<ShortArray>()
        val client = CaptureTranslationSocket(source, run, ticket, { authorized }, { attaches++; tap.attach() }, { url, events ->
            assertEquals("wss://fixture.invalid/capture-translation", url); connections++; listener = events; wire
        }, {}, { samples -> played += samples.copyOf(); erased += samples }, { now }, schedule = { ticks = it; Closeable { timerClosed = true } })
        init { client.connect(); listener.opened() }
        fun message(vararg values: Pair<String, Any?>) = listener.message(json.toJson(mapOf("capture_id" to source.capture, "run_id" to run.id, "generation" to 1) + values))
        fun ready() = message("type" to "ready", "configuration" to mapOf("source_language" to config.sourceLanguage, "target_language" to config.targetLanguage, "mode" to config.mode, "audio" to config.audio, "save_translations" to config.saveTranslations, "model" to config.model, "region" to config.region))
        fun ack(sequence: Long) = message("type" to "ack", "sequence" to sequence)
        fun pcm(count: Int = 1600) { tap.offer(ShortArray(count) { 1234 }, count) }
        fun complete(response: String, direction: String = "forward") = message("type" to "response_completed", "response_id" to response, "direction" to direction)
        override fun close() { client.close(); tap.close() }
    }
    @Test fun authenticatesBeforeAttachingAndCopiesBinaryPcm() {
        Fixture().use { f ->
            assertEquals(0, f.attaches); assertTrue(f.wire.messages.single().contains("private-ticket"))
            f.ready(); assertEquals(1, f.attaches); f.pcm(); f.ticks()
            val bytes = ByteBuffer.wrap(f.wire.frames.single()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, bytes.int); assertEquals(1234.toShort(), bytes.short)
            assertTrue(f.wire.erased.single().all { it == 0.toByte() })
        }
    }
    @Test fun manualTailIsDrainedBeforeEndAndReverseAttachesFreshTurn() {
        Fixture(true).use { f ->
            f.ready(); f.client.begin("forward"); f.ack(1)
            f.pcm(533); f.client.endTurn(); f.ticks()
            assertEquals(4 + 544 * 2, f.wire.frames.single().size)
            assertTrue(f.wire.messages.last().contains("\"sequence\":3")); assertTrue(f.wire.messages.last().contains("\"type\":\"end\""))
            assertEquals("awaiting", f.client.state.phase)
            f.ack(2); f.ack(3); f.complete("forward-result")
            f.client.begin("reverse"); assertEquals(2, f.attaches)
            assertTrue(f.wire.messages.last().contains("\"direction\":\"reverse\""))
        }
    }
    @Test fun emptyTurnCanReturnToReadyWithoutWaitingForText() {
        Fixture(true).use { f ->
            f.ready(); f.client.begin("forward"); f.ack(1); f.client.endTurn(); f.ticks()
            f.message("type" to "turn_empty", "direction" to "forward", "sequence" to 2)
            assertEquals("ready", f.client.state.phase)
        }
    }
    @Test fun duplicateResponseCannotUnlockNextUtterance() {
        Fixture(true).use { f ->
            f.ready(); f.client.begin("forward"); f.ack(1); f.client.endTurn(); f.ticks(); f.ack(2); f.complete("old")
            f.client.begin("forward"); f.ack(3); f.client.endTurn(); f.ticks(); f.ack(4); f.complete("old")
            assertEquals("awaiting", f.client.state.phase)
            f.complete("new"); assertEquals("ready", f.client.state.phase)
        }
    }
    @Test fun translatedAudioIsCopiedThenErasedAndRequiresConsent() {
        for (allowed in listOf(false, true)) Fixture(sound = allowed).use { f ->
            f.ready(); f.message("type" to "audio", "direction" to "forward", "response_id" to "r", "item_id" to "i", "audio" to "AQABAA==", "sample_rate" to 24000)
            if (allowed) { assertArrayEquals(shortArrayOf(1, 1), f.played.single()); assertArrayEquals(shortArrayOf(0, 0), f.erased.single()) }
            else { assertTrue(f.played.isEmpty()); assertEquals("incomplete", f.client.state.phase) }
        }
    }
    @Test fun duplicateFinalWrongSourceAndUnexpectedAcknowledgementFailClosed() {
        for (kind in listOf("source", "ack", "duplicate")) Fixture().use { f ->
            f.ready()
            when (kind) {
                "source" -> f.message("type" to "target_final", "capture_id" to UUID.randomUUID().toString())
                "ack" -> f.ack(999)
                else -> repeat(2) { f.message("type" to "target_final", "direction" to "forward", "response_id" to "r", "item_id" to "i", "text" to "Private translation") }
            }
            assertEquals("incomplete", f.client.state.phase); assertTrue(f.client.state.finals.isEmpty())
            assertEquals(1, f.wire.closes)
        }
    }
    @Test fun revocationErasesContentAndClosesOnlyTranslationTap() {
        Fixture().use { f ->
            f.ready(); f.message("type" to "target_final", "direction" to "forward", "response_id" to "r", "item_id" to "i", "text" to "Private text")
            f.authorized = false; f.ticks()
            assertEquals("incomplete", f.client.state.phase); assertTrue(f.client.state.finals.isEmpty()); assertTrue(f.timerClosed)
            assertEquals(1, f.connections)
        }
    }
    @Test fun finishingWaitsForBackendReceiptWhileOriginalTapCanContinue() {
        Fixture().use { f ->
            f.ready(); f.pcm(16); f.client.finish(); f.ticks()
            assertEquals("finishing", f.client.state.phase)
            assertTrue(f.wire.messages.last().contains("\"type\":\"finish\""))
            f.message("type" to "finished", "status" to "stopped", "complete" to true)
            assertEquals("stopped", f.client.state.phase)
            f.tap.attach().use { next -> f.pcm(); next.poll()!!.use { assertEquals(1600, it.samples.size) } }
        }
    }
    @Test fun overflowBoundsUnacknowledgedFramesAndDoesNotReconnect() {
        Fixture().use { f ->
            f.ready(); repeat(4) { f.pcm() }; f.ticks()
            assertEquals(4, f.wire.frames.size)
            repeat(5) { f.pcm() }; f.ticks()
            assertEquals("incomplete", f.client.state.phase); assertEquals(1, f.connections)
        }
    }
    @Test fun acknowledgementTimeoutEndsWithoutAudioReplay() {
        Fixture().use { f ->
            f.ready(); f.pcm(); f.ticks(); f.now += 5001; f.ticks()
            assertEquals("unknown", f.client.state.phase); assertEquals(1, f.wire.frames.size)
        }
    }
    @Test fun queuedNetworkBackpressureEndsOnlyTranslation() {
        Fixture().use { f -> f.ready(); f.wire.queuedBytes = 64001; f.pcm(); f.ticks(); assertEquals("incomplete", f.client.state.phase) }
    }
    @Test fun missingTurnResponseAndStartupAreBounded() {
        Fixture().use { f -> f.now += 45001; f.ticks(); assertEquals("unknown", f.client.state.phase); assertEquals(0, f.attaches) }
        Fixture(true).use { f ->
            f.ready(); f.client.begin("forward"); f.ack(1); f.client.endTurn(); f.ticks(); f.ack(2)
            f.now += 45001; f.ticks(); assertEquals("unknown", f.client.state.phase)
        }
    }
}
