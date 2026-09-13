package com.we.meet.data.capture

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking source. stop() must release a blocked read; close() releases the device. */
interface CapturePcmSource : Closeable {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}

data class CapturePumpOutcome(val interrupted: Boolean, val failed: Boolean, val paddedFrames: Int)

/** Run once on a dedicated IO worker, never on the UI thread. No network work in [persist]. */
class CapturePcmPump(
    private val source: CapturePcmSource,
    private val persist: (ShortArray) -> Unit,
    private val authorized: () -> Boolean,
    private val tap: CapturePcmTap? = null,
) {
    private val stopped = AtomicBoolean()
    private val interrupted = AtomicBoolean()
    private val consumed = AtomicBoolean()
    private val startup = Any()

    fun requestStop(unexpected: Boolean = false) {
        if (unexpected) { interrupted.set(true); tap?.close() }
        synchronized(startup) {
            stopped.set(true)
            runCatching { source.stop() }.onFailure { interrupted.set(true) }
        }
    }

    fun run(): CapturePumpOutcome {
        check(consumed.compareAndSet(false, true)) { "Audio pump cannot be restarted" }
        val readBuffer = ShortArray(1600) // 100 ms, independent of the five-second durable chunk.
        val chunk = ShortArray(CaptureWave.CHUNK_FRAMES)
        var used = 0
        var failed = false
        var persistenceFailed = false
        var padded = 0
        fun save(samples: ShortArray) {
            try {
                check(authorized()) { "Capture authority changed" }
                persist(samples)
            } catch (error: Exception) {
                persistenceFailed = true
                throw error
            } finally { samples.fill(0) }
        }
        try {
            synchronized(startup) {
                if (!stopped.get()) {
                    check(authorized())
                    source.start()
                }
            }
            while (!stopped.get()) {
                check(authorized()) { "Capture authority changed" }
                val count = source.read(readBuffer)
                if (count <= 0 && stopped.get()) break
                check(count in 1..readBuffer.size) { "Audio input stopped producing samples" }
                // A hardware interruption invalidates the in-flight read; a user stop drains it.
                if (interrupted.get()) break
                check(authorized()) { "Capture authority changed during read" }
                tap?.offer(readBuffer, count)
                var offset = 0
                while (offset < count) {
                    val take = minOf(count - offset, chunk.size - used)
                    readBuffer.copyInto(chunk, used, offset, offset + take)
                    used += take
                    offset += take
                    if (used == chunk.size) {
                        save(chunk.copyOf())
                        used = 0
                        chunk.fill(0)
                    }
                }
            }
        } catch (_: Exception) {
            interrupted.set(true)
            failed = true
        } finally {
            requestStop()
            // Preserve the already-read tail after input failure, but never retry a failed DB write.
            if (used > 0 && !persistenceFailed) {
                try {
                    padded = (16 - used % 16) % 16
                    save(chunk.copyOf(used + padded))
                } catch (_: Exception) { interrupted.set(true); failed = true }
            }
            runCatching { source.close() }.onFailure { interrupted.set(true); failed = true }
            if (failed || interrupted.get()) tap?.close() else tap?.finish()
            readBuffer.fill(0)
            chunk.fill(0)
        }
        return CapturePumpOutcome(interrupted.get(), failed, padded)
    }
}
