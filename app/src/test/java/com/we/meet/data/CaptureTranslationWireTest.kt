package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.CaptureTranslationRepository
import com.we.meet.data.repository.CaptureTranslationSource
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp masking/copy/ordering against an isolated RFC6455 loopback peer. */
class CaptureTranslationWireTest {
    private fun id() = UUID.randomUUID().toString()
    private val json = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Any::class.java)
    private fun InputStream.byte() = read().also { check(it >= 0) }
    private fun InputStream.bytes(count: Int) = ByteArray(count).also { value -> var offset = 0; while (offset < count) { val got = read(value, offset, count - offset); check(got > 0); offset += got } }
    private fun frame(input: InputStream): Pair<Int, ByteArray> {
        val opcode = input.byte(); require(opcode and 128 != 0)
        val length = input.byte(); require(length and 128 != 0)
        val size = when (val short = length and 127) { 126 -> input.byte() * 256 + input.byte(); 127 -> error("Oversized fixture frame"); else -> short }
        require(size <= 8192)
        val mask = input.bytes(4)
        return (opcode and 15) to input.bytes(size).also { bytes -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() } }
    }
    private fun send(output: OutputStream, value: Map<String, Any>) {
        val bytes = json.toJson(value).toByteArray(Charsets.UTF_8)
        output.write(0x81)
        if (bytes.size < 126) output.write(bytes.size) else { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
        output.write(bytes); output.flush()
    }
    private fun decoded(bytes: ByteArray) = json.fromJson(bytes.toString(Charsets.UTF_8)) as Map<*, *>
    private fun until(action: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!action() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(action())
    }
    @Test fun nativeWebSocketPreservesTailAndKeepsOriginalSourceOpen() {
        val source = CaptureTranslationSource(id(), id(), id(), "android", id())
        val config = CaptureTranslationConfigDto("zh", "en", "push_to_talk", false, false, CaptureTranslationRepository.MODEL, "cn-beijing")
        val run = CaptureTranslationRunDto(id(), source.capture, 1, 2, config, "starting", OffsetDateTime.now().plusSeconds(30).toString(), null, "")
        val ticket = CaptureTranslationTicketDto("loopback-only-ticket", "wss://fixture.invalid/capture-translation", run.deadline, CaptureTranslationTicketSourceDto(run.id, source.capture, source.viewer, source.device, 1, 2))
        val outcome = CompletableFuture<Unit>()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Thread({
                try { server.accept().use { connection ->
                    connection.soTimeout = 5000
                    val input = connection.getInputStream(); val output = connection.getOutputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) { check(headers.length < 8192); headers.append(input.byte().toChar()) }
                    assertTrue(headers.startsWith("GET /capture-translation HTTP/1.1"))
                    assertFalse(headers.contains("Authorization:", true)); assertFalse(headers.contains("loopback-only-ticket"))
                    val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); output.flush()
                    val auth = frame(input); assertEquals(1, auth.first); assertEquals(ticket.ticket, decoded(auth.second)["ticket"])
                    val envelope = mapOf("capture_id" to source.capture, "run_id" to run.id, "generation" to 1)
                    send(output, envelope + mapOf("type" to "ready", "configuration" to config))
                    val begin = decoded(frame(input).second); assertEquals("begin", begin["type"]); assertEquals(1.0, begin["sequence"])
                    send(output, envelope + mapOf("type" to "ack", "sequence" to 1))
                    val pcm = frame(input); assertEquals(2, pcm.first); assertEquals(4 + 544 * 2, pcm.second.size)
                    val buffer = ByteBuffer.wrap(pcm.second).order(ByteOrder.LITTLE_ENDIAN); assertEquals(2, buffer.int)
                    repeat(533) { assertEquals(1234.toShort(), buffer.short) }; repeat(11) { assertEquals(0.toShort(), buffer.short) }
                    send(output, envelope + mapOf("type" to "ack", "sequence" to 2))
                    val end = decoded(frame(input).second); assertEquals("end", end["type"]); assertEquals(3.0, end["sequence"])
                    send(output, envelope + mapOf("type" to "ack", "sequence" to 3))
                    send(output, envelope + mapOf("type" to "target_final", "direction" to "forward", "response_id" to "r", "item_id" to "i", "text" to "Confirmed translation"))
                    send(output, envelope + mapOf("type" to "response_completed", "direction" to "forward", "response_id" to "r"))
                    val finish = decoded(frame(input).second); assertEquals("finish", finish["type"]); assertEquals(4.0, finish["sequence"])
                    send(output, envelope + mapOf("type" to "finished", "status" to "stopped", "complete" to true))
                }; outcome.complete(Unit) } catch (error: Throwable) { outcome.completeExceptionally(error) }
            }, "translation-loopback-fixture").apply { isDaemon = true; start() }
            CapturePcmTap().use { tap ->
                CaptureTranslationSocket(source, run, ticket, { true }, { tap.attach() }, { _, listener ->
                    // Only this test substitutes ws:// loopback; production ticket validation requires WSS.
                    OkHttpCaptureTranslationWire.open("ws://127.0.0.1:${server.localPort}/capture-translation", listener)
                }, {}).use { client ->
                    client.connect(); until { client.state.phase == "ready" }
                    client.begin("forward"); tap.offer(ShortArray(533) { 1234 }, 533); client.endTurn()
                    until { client.state.phase == "ready" && client.state.finals.size == 1 }
                    client.finish(); until { client.state.phase == "stopped" }
                    outcome.get(5, TimeUnit.SECONDS)
                    tap.attach().use { next -> tap.offer(ShortArray(1600), 1600); next.poll()!!.close() }
                }
            }
        }
    }
}
