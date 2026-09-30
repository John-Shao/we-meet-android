package com.we.meet.feature.assistant.aicall.rtc

/** Callbacks can arrive in any order. Media starts only after our config is acknowledged. */
internal class OmniHandshake {
    var connected = false
    var channelOpen = false
    var sessionCreated = false
    var updateSent = false
        private set
    var ready = false
        private set
    private var closed = false

    fun takeConfiguration(): Boolean {
        if (closed || updateSent || !connected || !channelOpen || !sessionCreated) return false
        updateSent = true
        return true
    }

    fun acknowledge(): Boolean {
        if (closed || !updateSent || ready) return false
        ready = true
        return true
    }

    fun close() {
        closed = true
        ready = false
    }
}
