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
import android.view.Surface

import androidx.core.content.ContextCompat

/**
 * What the UI needs from whole-file playback.
 *
 * A seam rather than a concrete class so a fixture can drive the controls
 * without opening a real stream, exactly as the capture player does.
 */
interface WholeFilePlayback {
    fun isPreparing(): Boolean = false
    fun failure(): Throwable? = null
    fun videoAspectRatio(): Float = 16f / 9f
    fun setSurface(surface: Surface?) {}
    fun setMuted(muted: Boolean) {}
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
    private var surface: Surface? = null
    private var preparing = false
    private var prepared = false
    private var error: Throwable? = null
    private var pendingMs = 0L
    private var pendingRate = 1f
    private var seeking = false
    private var playWhenReady = false
    private var muted = false

    @Synchronized override fun setMuted(muted: Boolean) {
        this.muted = muted
        val volume = if (muted) 0f else 1f
        if (!closed) player?.setVolume(volume, volume)
    }

    @Synchronized override fun isPreparing() = preparing
    @Synchronized override fun failure() = error
    @Synchronized override fun videoAspectRatio(): Float =
        player?.takeIf { prepared && it.videoWidth > 0 && it.videoHeight > 0 }
            ?.let { it.videoWidth.toFloat() / it.videoHeight } ?: (16f / 9f)
    @Synchronized override fun setSurface(surface: Surface?) {
        this.surface = surface
        if (!closed) player?.setSurface(surface)
    }
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
    // Native getters can report an asynchronous error in Preparing; catching
    // exceptions does not prevent the player from entering its Error state.
    override fun durationMs(): Long = runCatching { if (prepared) requireNotNull(player).duration.toLong() else 0L }
        .getOrDefault(0L)
        .coerceAtLeast(0L)

    @Synchronized
    override fun isPlaying(): Boolean = prepared && runCatching { requireNotNull(player).isPlaying }.getOrDefault(false)

    @Synchronized
    override fun positionMs(): Long = runCatching { if (prepared && !seeking) requireNotNull(player).currentPosition.toLong() else pendingMs }
        .getOrDefault(pendingMs)
        .coerceAtLeast(0L)

    /** Start or resume from [fromMs]; safe to call when already playing. */
    @Synchronized
    override fun play(fromMs: Long, rate: Float) {
        check(!closed) { "player is closed" }
        pendingMs = fromMs.coerceAtLeast(0L)
        pendingRate = rate
        playWhenReady = true
        if (prepared) startPrepared() else if (!preparing) prepare()
    }

    private fun startPrepared() {
        val current = checkNotNull(player)
        current.playbackParams = PlaybackParams().setSpeed(pendingRate).setPitch(1f)
        seeking = true
        current.seekTo(pendingMs, MediaPlayer.SEEK_CLOSEST)
        current.start()
    }

    @Synchronized
    override fun pause() {
        if (closed) return
        playWhenReady = false
        if (prepared) runCatching { player?.pause() }
    }

    @Synchronized
    override fun seekTo(milliseconds: Long) {
        if (closed) return
        pendingMs = milliseconds.coerceAtLeast(0L)
        if (prepared) {
            seeking = true
            player?.seekTo(pendingMs, MediaPlayer.SEEK_CLOSEST)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        preparing = false
        prepared = false
        playWhenReady = false
        player?.let { value ->
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
    private fun prepare() {
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
            val volume = if (muted) 0f else 1f
            setVolume(volume, volume)
        }
        player = value
        value.setSurface(surface)
        value.setOnSeekCompleteListener { seeking = false }
        value.setOnErrorListener { _, what, extra ->
            error = IllegalStateException("media playback failed: $what/$extra")
            close()
            true
        }
        value.setOnPreparedListener {
            if (!closed) {
                preparing = false
                prepared = true
                if (playWhenReady) runCatching { startPrepared() }.onFailure {
                    error = it
                    close()
                }
            }
        }
        value.setDataSource(url)
        preparing = true
        value.prepareAsync()
    }
}
