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
import android.os.Build
import android.os.SystemClock
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
    private var startThresholdFrames = 1
    private var closed = false
    private var muted = false
    private var registered = false
    private var focused = false
    @get:Synchronized
    val pendingSamples: Long get() = (written - ((track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL)).coerceAtLeast(0)
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
            val canSetThreshold = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val bufferBytes = if (canSetThreshold) 144000 else maxOf(minimum, 9600)
            val value = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build()
            track = value
            check(value.state == AudioTrack.STATE_INITIALIZED)
            // Capacity is not the desired start threshold: waiting for three seconds
            // of PCM silenced short replies, then the caller's drain timed out.
            startThresholdFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                value.setStartThresholdInFrames(1)
            } else value.bufferSizeInFrames
            value.play()
            written = 0
        } catch (error: Exception) { close(); throw error }
    }
    @Synchronized override fun play(samples: ShortArray) {
        check(!closed)
        if (muted) return
        try {
            check(samples.size in 1..24000)
            writeAll(samples)
        } catch (error: Exception) { close(); throw error }
    }
    private fun writeAll(samples: ShortArray) {
        val output = requireNotNull(track)
        val deadline = SystemClock.elapsedRealtime() + 2000
        var offset = 0
        while (offset < samples.size) {
            check(output.playState == AudioTrack.PLAYSTATE_PLAYING)
            val count = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(count >= 0)
            written += count
            offset += count
            if (offset < samples.size) {
                // A partial non-blocking write means backpressure, not a broken player.
                check(SystemClock.elapsedRealtime() < deadline) { "Translation audio write stalled" }
                SystemClock.sleep(5)
            }
        }
    }
    @Synchronized fun finishTurn() {
        check(!closed)
        if (muted) return
        // Android 10/11 cannot set a start threshold. Pad only an underfilled
        // final buffer so even a sub-buffer reply starts, without another utterance.
        val pending = pendingSamples
        if (pending in 1 until startThresholdFrames.toLong()) {
            try { writeAll(ShortArray(startThresholdFrames - pending.toInt())) }
            catch (error: Exception) { close(); throw error }
        }
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
