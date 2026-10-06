package com.we.meet.feature.assistant.aicall.rtc

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.we.meet.feature.assistant.BuildConfig
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Debug levels and output configuration only; never retain PCM, speech or credentials. */
internal class OmniPlaybackDiagnostics(context: Context, private val transport: String, private val source: String) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var windowAt = 0L
    private var count = 0
    private var squares = 0.0
    private var peak = 0f

    fun sample(rms: Float) {
        if (!BuildConfig.DEBUG || !rms.isFinite() || rms <= 0.01f) return
        // Diagnostic failures must never terminate a call.
        runCatching {
            count++; squares += rms.toDouble() * rms; peak = maxOf(peak, rms)
            val now = SystemClock.elapsedRealtime()
            if (now - windowAt < 1000) return
            windowAt = now
            val dbfs = 20 * log10(sqrt(squares / count).coerceAtLeast(1e-9))
            val usages = audio.activePlaybackConfigurations.map { it.audioAttributes.usage }.distinct()
            val route = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type else null
            Log.i("OmniPlayback", String.format(Locale.ROOT,
                "%s source=%s rmsDbfs=%.1f peakRms=%.3f mode=%d usage=%s route=%s call=%d/%d media=%d/%d",
                transport, source, dbfs, peak, audio.mode, usages, route,
                audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL), audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
            count = 0; squares = 0.0; peak = 0f
        }
    }
}
