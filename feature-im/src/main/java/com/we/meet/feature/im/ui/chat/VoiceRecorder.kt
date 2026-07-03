package com.we.meet.feature.im.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Minimal hold-to-talk recorder: AAC in an MP4 (.m4a) container written to the
 * cache dir. One recording at a time; [stop] returns the file + duration, or
 * null if the clip was too short / failed (caller discards).
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L

    /** Begin recording; returns false if the recorder couldn't start. */
    fun start(): Boolean {
        stopInternal(discard = true)
        val file = File(context.cacheDir, "voice_${System.nanoTime()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            true
        } catch (_: Throwable) {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /** Recorded clip + duration in ms, or null if too short / failed. */
    fun stop(minDurationMs: Long = 800L): Result? {
        val file = outputFile
        val durationMs = System.currentTimeMillis() - startedAt
        val ok = stopInternal(discard = false)
        if (!ok || file == null || durationMs < minDurationMs || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        return Result(file, durationMs)
    }

    fun cancel() = stopInternal(discard = true)

    private fun stopInternal(discard: Boolean): Boolean {
        val rec = recorder ?: return false
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        if (discard) {
            outputFile?.delete()
            outputFile = null
        }
        return ok
    }

    data class Result(val file: File, val durationMs: Long)
}
