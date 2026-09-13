package com.we.meet.data.capture

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
import java.io.Closeable

interface CaptureTranslationOutput : Closeable {
    fun open()
    /** Synchronous copy, never retain the caller's PCM array. */
    fun play(samples: ShortArray)
    fun mute(value: Boolean)
}

/** Bounded output-only stream. Focus loss and noisy routing terminate it without auto-resume. */
class AndroidTranslationOutput(context: Context, private val interrupted: () -> Unit) : CaptureTranslationOutput {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var track: AudioTrack? = null
    private var written = 0L
    private var closed = false
    private var muted = false
    private var registered = false
    private var focused = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ if (it != AudioManager.AUDIOFOCUS_GAIN) interrupt() }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt() }
    }
    private fun interrupt() {
        val notify = synchronized(this) { if (closed) false else { close(); true } }
        if (notify) interrupted()
    }
    @Synchronized override fun open() {
        check(!closed && track == null)
        try {
            if (!registered) {
                ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            if (!focused) {
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                focused = true
            }
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum in 1..144000)
            check(!closed)
            val value = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(144000).build()
            track = value
            check(value.state == AudioTrack.STATE_INITIALIZED); value.play()
            written = 0
        } catch (error: Exception) { close(); throw error }
    }
    @Synchronized override fun play(samples: ShortArray) {
        check(!closed)
        if (muted) return
        try {
            val output = requireNotNull(track)
            val played = output.playbackHeadPosition.toLong() and 0xffffffffL
            check(samples.size in 1..24000 && output.playState == AudioTrack.PLAYSTATE_PLAYING && written >= played && written + samples.size - played <= 72000)
            check(output.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING) == samples.size)
            written += samples.size
        } catch (error: Exception) { close(); throw error }
    }
    private fun releaseTrack() {
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; it.release() }
        track = null; written = 0
    }
    @Synchronized override fun mute(value: Boolean) {
        check(!closed)
        if (muted == value) return
        muted = value
        if (value) releaseTrack() else open()
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; releaseTrack()
        if (focused) { focused = false; manager.abandonAudioFocusRequest(focus) }
        if (registered) { registered = false; context.unregisterReceiver(noisy) }
    }
}
