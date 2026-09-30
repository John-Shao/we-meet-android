package com.we.meet.feature.assistant.aicall.rtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread recovery window; duplicate disconnect callbacks cannot extend it. */
internal class OmniConnectionRecovery(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
) {
    private var timer: Job? = null
    private var closed = false

    fun disconnected() {
        if (closed || timer != null) return
        timer = scope.launch {
            delay(10_000)
            onTimeout()
        }
    }

    fun connected() {
        timer?.cancel()
        timer = null
    }

    fun close() {
        closed = true
        connected()
    }
}
