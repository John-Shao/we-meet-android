package com.we.meet.ui.ai

import java.io.ByteArrayOutputStream

/** Unclassified input is bounded independently of the lifetime of the connection. */
internal class BilingualProbeBuffer(private val maximumBytes: Int = 320000) {
    private val buffer = ByteArrayOutputStream()
    var generation = 0L
        private set

    fun size() = buffer.size()
    fun toByteArray(): ByteArray = buffer.toByteArray()

    /** Start a new segment when persistent noise exhausts the classification window. */
    fun append(bytes: ByteArray): Boolean {
        require(bytes.size <= maximumBytes)
        val overflow = buffer.size() + bytes.size > maximumBytes
        if (overflow) reset()
        buffer.write(bytes)
        return overflow
    }

    fun reset() {
        buffer.reset()
        generation++
    }
}
