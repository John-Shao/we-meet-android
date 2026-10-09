package com.we.meet.ui.ai

import java.nio.ByteBuffer
import java.util.ArrayDeque

/** 16 kHz mono PCM, drained by the WebRTC 10 ms external capture callback. */
internal class BilingualPcmBuffer {
    private val packets = ArrayDeque<ByteArray>()
    private var offset = 0
    private var queued = 0
    private var written = 0L
    private var consumed = 0L
    private var closed = false

    @Synchronized fun append(bytes: ByteArray): Long {
        check(!closed && bytes.size % 2 == 0 && queued + bytes.size <= 320000)
        if (bytes.isNotEmpty()) packets.addLast(bytes.copyOf())
        queued += bytes.size; written += bytes.size
        return written
    }
    @Synchronized fun drained(mark: Long) = consumed >= mark
    @Synchronized fun clear() { packets.clear(); offset = 0; queued = 0; consumed = written }
    @Synchronized fun close() { closed = true; clear() }
    @Synchronized fun fill(buffer: ByteBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) {
            val packet = packets.peekFirst()
            if (packet == null) { buffer.put(0.toByte()); continue }
            val count = minOf(buffer.remaining(), packet.size - offset)
            buffer.put(packet, offset, count)
            offset += count; queued -= count; consumed += count
            if (offset == packet.size) { packets.removeFirst(); offset = 0 }
        }
        buffer.rewind()
    }
}
