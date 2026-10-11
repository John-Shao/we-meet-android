package com.we.meet.data.voiceprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Entire bounded sample stays in memory; no private WAV paths or network media URLs. */
internal class VoiceprintAudioOutput(context: Context, private val onStopped: () -> Unit) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var track: AudioTrack? = null
    private var length = 0
    private var registered = false
    private var focused = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ if (it != AudioManager.AUDIOFOCUS_GAIN) { stop(); onStopped() } }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { stop(); onStopped() } }
    }
    @Synchronized fun play(wav: ByteArray) {
        stop()
        val samples = VoiceprintWave.pcm(wav)
        try {
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focused = true
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            track = output
            check(output.state == AudioTrack.STATE_NO_STATIC_DATA)
            check(output.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) == samples.size)
            length = samples.size
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            output.play()
        } catch (error: Exception) { stop(); throw error }
        finally { samples.fill(0) }
    }
    @Synchronized fun completed(): Boolean = try {
        track?.let { it.playbackHeadPosition >= length && length > 0 } == true
    } catch (_: IllegalStateException) {
        stop(); onStopped(); false
    }
    @Synchronized fun stop() {
        track?.let { runCatching(it::stop); runCatching(it::release) }; track = null; length = 0
        if (registered) runCatching { context.unregisterReceiver(noisy) }
        if (focused) runCatching { manager.abandonAudioFocusRequest(focus) }
        registered = false; focused = false
    }
}
