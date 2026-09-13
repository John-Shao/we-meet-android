package com.we.meet.data.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Construct only after foreground microphone authority is established. Never auto-reopens input. */
class AndroidCapturePcmSource private constructor(
    private val record: AudioRecord,
    context: Context,
) : CapturePcmSource {
    private val failed = AtomicBoolean()
    private val released = AtomicBoolean()
    private val stopping = AtomicBoolean()
    @Volatile private var device: Int? = null
    var onInterrupted: () -> Unit = {}

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (configs.any { it.clientAudioSessionId == record.audioSessionId &&
                    (it.isClientSilenced || it.clientFormat.sampleRate != CaptureWave.SAMPLE_RATE ||
                        it.clientFormat.channelCount != 1 || it.clientFormat.encoding != AudioFormat.ENCODING_PCM_16BIT) }) interrupt()
        }
    }
    private val routingCallback = AudioRouting.OnRoutingChangedListener {
        val now = record.routedDevice?.id
        val before = device
        if (before != null && now != before) interrupt()
        else device = now
    }

    init {
        try {
            record.registerAudioRecordingCallback(ContextCompat.getMainExecutor(context), recordingCallback)
            record.addOnRoutingChangedListener(routingCallback, Handler(Looper.getMainLooper()))
        } catch (error: Exception) { record.release(); throw error }
    }

    private fun interrupt() {
        if (!stopping.get() && !released.get() && failed.compareAndSet(false, true)) {
            stop()
            onInterrupted()
        }
    }

    @Synchronized override fun start() {
        check(!released.get() && !stopping.get() && !failed.get())
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        device = record.routedDevice?.id
        if (record.activeRecordingConfiguration?.isClientSilenced == true) interrupt()
        check(!failed.get()) { "Microphone input was interrupted" }
    }

    override fun read(buffer: ShortArray): Int {
        check(!failed.get() && !released.get())
        return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING).also {
            check(!failed.get()) { "Microphone input was interrupted" }
        }
    }

    @Synchronized override fun stop() {
        stopping.set(true)
        if (!released.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
    }

    @Synchronized override fun close() {
        if (released.get()) return
        try { stop() } finally {
            released.set(true)
            runCatching { record.unregisterAudioRecordingCallback(recordingCallback) }
            runCatching { record.removeOnRoutingChangedListener(routingCallback) }
            record.release()
            onInterrupted = {}
        }
    }

    companion object {
        fun open(context: Context): AndroidCapturePcmSource {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            val minimum = AudioRecord.getMinBufferSize(CaptureWave.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(CaptureWave.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, CaptureWave.SAMPLE_RATE * 2))
                .build()
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == CaptureWave.SAMPLE_RATE &&
                    record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                return AndroidCapturePcmSource(record, context)
            } catch (error: Exception) { runCatching { record.release() }; throw error }
        }
    }
}
