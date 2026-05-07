package com.we.meet.audio

/**
 * Tiny in-memory holder for the user's last audio-output choice so it
 * survives Preview → Room screen handoff. Process-scoped (not persisted
 * across kills); a meeting session is short enough that this is fine.
 */
object AudioOutputStore {
    @Volatile
    var lastChoice: AudioOutput = AudioOutput.Speaker
}
