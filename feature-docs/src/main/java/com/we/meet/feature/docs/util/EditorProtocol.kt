package com.we.meet.feature.docs.util

/** Immutable host state: absence of a handshake never means a clean document. */
data class EditorProtocol(
    val docId: String,
    val instanceId: String,
    val phase: Phase = Phase.WAITING,
    val requestId: String? = null,
) {
    enum class Phase { WAITING, READY, SAVING, FAILED, UNSUPPORTED, CLOSED }
    val interactive: Boolean get() = phase == Phase.READY || phase == Phase.FAILED
    val awaitingReady: Boolean get() = phase == Phase.WAITING || phase == Phase.UNSUPPORTED

    fun ready(doc: String, instance: String, version: Int, saveConfirmation: Boolean): EditorProtocol =
        if (awaitingReady && doc == docId && instance == instanceId && version == 2 && saveConfirmation)
            copy(phase = Phase.READY) else this

    fun save(request: String): EditorProtocol =
        if (interactive) copy(phase = Phase.SAVING, requestId = request) else this

    fun result(doc: String, instance: String, request: String, success: Boolean): EditorProtocol =
        if (phase == Phase.SAVING && doc == docId && instance == instanceId && request == requestId)
            copy(phase = if (success) Phase.CLOSED else Phase.FAILED, requestId = null) else this

    fun timedOut(): EditorProtocol = when (phase) {
        Phase.WAITING -> copy(phase = Phase.UNSUPPORTED)
        Phase.SAVING -> copy(phase = Phase.FAILED, requestId = null)
        else -> this
    }
}
