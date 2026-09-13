package com.we.meet.data.capture

/** One local player; starting capture or another player immediately releases the old output. */
object CapturePlaybackRegistry {
    private var current: CapturePlaybackEngine? = null
    fun activate(engine: CapturePlaybackEngine) {
        val previous = synchronized(this) { current.also { current = engine } }
        if (previous !== engine) previous?.close()
    }
    fun release(engine: CapturePlaybackEngine) { synchronized(this) { if (current === engine) current = null } }
    fun stopAll() {
        val previous = synchronized(this) { current.also { current = null } }
        previous?.close()
    }
}
