package com.we.meet.data.capture

import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID

/** Optional 100 ms copies of the existing microphone. Never performs network or disk IO. */
class CapturePcmTap(private val authorized: () -> Boolean = { true }) : Closeable {
    enum class State { RUNNING, FINISHED, OVERFLOW, CLOSED }
    private class Channel {
        val generation = UUID.randomUUID().toString()
        var state = State.RUNNING
        val queued = ArrayDeque<ShortArray>()
        val partial = ShortArray(FRAMES)
        var used = 0
        var sequence = 0L
        var leased: Frame? = null
    }

    class Frame internal constructor(val sequence: Long, val samples: ShortArray, private val release: () -> Unit) : Closeable {
        override fun close() = release()
        override fun toString() = "CapturePcmFrame(<private>)"
    }

    interface Subscription : Closeable {
        val generation: String
        val state: State
        fun poll(): Frame?
        /** Flush this turn, while leaving the original microphone and recorder running. */
        fun finish()
    }

    private inner class Reader(private val channel: Channel) : Subscription {
        override val generation: String get() = channel.generation
        override val state: State get() = synchronized(this@CapturePcmTap) { checkAuthority(); channel.state }

        /** At most one leased frame; close it after copying/sending, including on failure. */
        override fun poll(): Frame? = synchronized(this@CapturePcmTap) {
            checkAuthority()
            if (active !== channel || channel.state !in setOf(State.RUNNING, State.FINISHED) || channel.leased != null) return@synchronized null
            val samples = channel.queued.pollFirst() ?: return@synchronized null
            val frame = Frame(++channel.sequence, samples) {
                synchronized(this@CapturePcmTap) {
                    samples.fill(0)
                    if (channel.leased?.samples === samples) channel.leased = null
                }
            }
            channel.leased = frame
            frame
        }

        override fun close() = synchronized(this@CapturePcmTap) {
            clear(channel, State.CLOSED)
            if (active === channel) active = null
        }
        override fun finish() = synchronized(this@CapturePcmTap) {
            checkAuthority()
            if (active === channel) finishChannel(channel)
        }
        override fun toString() = "CapturePcmSubscription(<private>)"

    }

    private var active: Channel? = null
    private var ended = false

    @Synchronized fun attach(): Subscription {
        checkAuthority()
        check(!ended) { "PCM source has ended" }
        active?.let { clear(it, State.CLOSED) }
        val channel = Channel()
        active = channel
        return Reader(channel)
    }

    @Synchronized fun offer(samples: ShortArray, count: Int) {
        checkAuthority()
        val channel = active ?: return
        if (channel.state != State.RUNNING) return
        if (count !in 1..samples.size || count > FRAMES) { clear(channel, State.CLOSED); return }
        var offset = 0
        while (offset < count && channel.state == State.RUNNING) {
            val take = minOf(count - offset, FRAMES - channel.used)
            samples.copyInto(channel.partial, channel.used, offset, offset + take)
            channel.used += take; offset += take
            if (channel.used == FRAMES) enqueue(channel, FRAMES)
        }
    }

    @Synchronized fun finish() {
        checkAuthority()
        ended = true
        val channel = active ?: return
        finishChannel(channel)
    }

    private fun finishChannel(channel: Channel) {
        if (channel.state != State.RUNNING) return
        if (channel.used > 0) enqueue(channel, channel.used + (16 - channel.used % 16) % 16)
        if (channel.state == State.RUNNING) channel.state = State.FINISHED
    }

    @Synchronized override fun close() {
        ended = true
        active?.let { clear(it, State.CLOSED) }
        active = null
    }

    private fun checkAuthority() {
        if (!runCatching(authorized).getOrDefault(false)) close()
    }

    private fun enqueue(channel: Channel, count: Int) {
        if (channel.queued.size + (if (channel.leased != null) 1 else 0) >= MAX_PENDING) {
            clear(channel, State.OVERFLOW)
            return
        }
        channel.queued.addLast(channel.partial.copyOf(count))
        channel.partial.fill(0); channel.used = 0
    }

    private fun clear(channel: Channel, state: State) {
        channel.queued.forEach { it.fill(0) }; channel.queued.clear()
        channel.partial.fill(0); channel.used = 0
        channel.leased?.samples?.fill(0); channel.leased = null
        channel.state = state
    }

    companion object {
        const val FRAMES = 1600
        const val MAX_PENDING = 4
    }
}
