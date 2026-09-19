package com.we.meet.data.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper

import androidx.core.content.ContextCompat

/**
 * What the UI needs from whole-file playback.
 *
 * A seam rather than a concrete class so a fixture can drive the controls
 * without opening a real stream, exactly as the capture player does.
 */
interface WholeFilePlayback {
    fun durationMs(): Long
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun play(fromMs: Long, rate: Float)
    fun pause()
    fun seekTo(milliseconds: Long)
    fun close()
}

/**
 * Playback for an imported file.
 *
 * An import lands as one sealed object, so the platform player streams it and
 * the storage service does the ranging. That is deliberately not the capture
 * engine: a live capture arrives as verified chunks with real gaps, which is why
 * that path decodes WAV into an `AudioTrack` and refuses to play across a hole.
 * Here there is one continuous object, so `MediaPlayer` gives exact seeking over
 * HTTP Range for free instead of a manifest and a chunk table.
 *
 * Audio focus and becoming-noisy handling mirror the capture output, because two
 * playback paths in one app must not behave differently when a call arrives.
 */
class UploadMediaEngine(
    context: Context,
    private val url: String,
    private val onInterrupted: () -> Unit,
) : WholeFilePlayback {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private var closed = false
    private var focused = false
    private var registered = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ value ->
            // A transient loss pauses; the reader can resume. A permanent one
            // tears the player down, matching the capture path.
            if (value == AudioManager.AUDIOFOCUS_LOSS) interrupt()
            else if (value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }, Handler(Looper.getMainLooper()))
        .build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
        }
    }

    private fun interrupt() {
        val notify = synchronized(this) {
            if (closed) false else {
                close()
                true
            }
        }
        if (notify) onInterrupted()
    }

    /** Total length in milliseconds, 0 until the source is prepared. */
    @Synchronized
    override fun durationMs(): Long = runCatching { requireNotNull(player).duration.toLong() }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    @Synchronized
    override fun isPlaying(): Boolean = runCatching { requireNotNull(player).isPlaying }.getOrDefault(false)

    @Synchronized
    override fun positionMs(): Long = runCatching { requireNotNull(player).currentPosition.toLong() }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    /** Start or resume from [fromMs]; safe to call when already playing. */
    @Synchronized
    override fun play(fromMs: Long, rate: Float) {
        if (closed) return
        val current = player ?: prepare()
        runCatching {
            current.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
            current.seekTo(fromMs.coerceAtLeast(0L).toInt())
            current.start()
        }.onFailure { close() }
    }

    @Synchronized
    override fun pause() {
        if (closed) return
        runCatching { player?.pause() }
    }

    @Synchronized
    override fun seekTo(milliseconds: Long) {
        if (closed) return
        runCatching { player?.seekTo(milliseconds.coerceAtLeast(0L).toInt()) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        player?.let { value ->
            runCatching { if (value.isPlaying) value.pause() }
            runCatching { value.reset() }
            runCatching { value.release() }
        }
        player = null
        if (focused) {
            focused = false
            runCatching { manager.abandonAudioFocusRequest(focus) }
        }
        if (registered) {
            registered = false
            runCatching { context.unregisterReceiver(noisy) }
        }
    }

    /** Preparing opens the network; a failure here surfaces as an error state. */
    @Synchronized
    private fun prepare(): MediaPlayer {
        if (!registered) {
            ContextCompat.registerReceiver(
                context,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        if (!focused) {
            val granted = manager.requestAudioFocus(focus)
            check(granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "audio focus refused"
            }
            focused = true
        }
        val value = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(url)
        }
        player = value
        value.prepare()
        check(!closed)
        return value
    }
}
