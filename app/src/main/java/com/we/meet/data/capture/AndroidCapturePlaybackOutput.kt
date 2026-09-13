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
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Foreground speech playback only. Focus loss/noisy routing closes the buffer and never resumes it. */
class AndroidCapturePlaybackOutput(context: Context, private val onInterrupted: () -> Unit) : CapturePlaybackOutput {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var track: AudioTrack? = null
    private var offset = 0L
    private var closed = false
    private var focused = false
    private var registered = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ value -> if (value != AudioManager.AUDIOFOCUS_GAIN) interrupt() }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
    } }
    private fun interrupt() {
        val notify = synchronized(this) {
            if (closed) false else { close(); true }
        }
        if (notify) onInterrupted()
    }
    @Synchronized override fun play(wave: ByteArray, offsetMs: Long, rate: Float) {
        check(!closed)
        val info = CaptureWave.inspect(wave)
        require(offsetMs in 0 until info.durationMs && rate in setOf(0.75f, 1f, 1.25f, 1.5f, 2f))
        try {
            track?.let { it.pause(); it.flush(); it.release() }
            track = null
            if (!registered) {
                ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
            if (!focused) {
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                focused = true
            }
            check(!closed)
            val begin = 44 + (offsetMs * 32).toInt()
            val count = wave.size - begin
            val value = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(CaptureWave.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(count).build()
            track = value
            check(value.state == AudioTrack.STATE_NO_STATIC_DATA || value.state == AudioTrack.STATE_INITIALIZED)
            check(value.write(wave, begin, count, AudioTrack.WRITE_BLOCKING) == count)
            value.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
            offset = offsetMs
            value.play()
        } catch (error: Throwable) { close(); throw error }
    }
    @Synchronized override fun positionMs(): Long {
        check(!closed)
        return offset + (requireNotNull(track).playbackHeadPosition.toLong() and 0xffffffffL) / 16
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        track?.let { value ->
            runCatching { value.pause() }
            runCatching { value.flush() }
            value.release()
        }
        track = null
        if (focused) { focused = false; manager.abandonAudioFocusRequest(focus) }
        if (registered) { registered = false; context.unregisterReceiver(noisy) }
    }
}
