package com.we.meet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import io.livekit.android.audio.AudioHandler

/**
 * Minimal [AudioHandler] that manages audio focus and forces the
 * [AudioManager.MODE_IN_COMMUNICATION] mode while a LiveKit [io.livekit.android.room.Room]
 * is active, and does **not** perform any device (speaker / earpiece) routing.
 *
 * Replaces the default [io.livekit.android.audio.AudioSwitchHandler], which on API 31+
 * routes devices via the deprecated `isSpeakerphoneOn` setter. That setter on S+ is
 * internally translated to `clearCommunicationDevice()` when called with `false`,
 * which silently undoes our own `setCommunicationDevice(BUILTIN_EARPIECE)` call
 * and leaves the user unable to switch to earpiece.
 *
 * With this handler installed, [AudioOutputController] is the single source of
 * truth for the active audio route.
 */
class CallFocusAudioHandler(context: Context) : AudioHandler {

    private val audioManager = checkNotNull(
        context.applicationContext.getSystemService(AudioManager::class.java)
    ) { "AudioManager unavailable" }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* no-op */ }

    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var audioRequest: android.media.AudioFocusRequest? = null
    private var started = false

    override fun start() {
        if (started) return
        savedMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }

        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
        started = true
    }

    override fun stop() {
        if (!started) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        audioRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        audioRequest = null
        runCatching { audioManager.mode = savedMode }
        started = false
    }
}
