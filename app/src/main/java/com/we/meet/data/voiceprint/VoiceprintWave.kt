package com.we.meet.data.voiceprint

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceprintAudioFormatException : IllegalArgumentException("voiceprint_audio_format_invalid")
class VoiceprintDurationException : IllegalArgumentException("voiceprint_duration_invalid")

/** Structural PCM check, not speech quality or identity evidence. No persistent audio files. */
object VoiceprintWave {
    const val SAMPLE_RATE = 24000
    const val MAX_BYTES = 484096
    const val MAX_FRAMES = SAMPLE_RATE * 10
    fun encode(samples: ShortArray): ByteArray {
        if (samples.size !in SAMPLE_RATE * 3..MAX_FRAMES) throw VoiceprintDurationException()
        val bytes = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(bytes.capacity() - 8)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bytes.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
        bytes.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 2)
        samples.forEach(bytes::putShort)
        return bytes.array()
    }
    fun duration(data: ByteArray): Long {
        if (data.size !in 44..MAX_BYTES) throw VoiceprintAudioFormatException()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int, count: Int) = String(data, at, count, Charsets.US_ASCII)
        fun invalid(): Nothing = throw VoiceprintAudioFormatException()
        if (text(0, 4) != "RIFF" || text(8, 4) != "WAVE" || buffer.getInt(4) != data.size - 8) invalid()
        var at = 12; var format = false; var payload: Int? = null
        while (at < data.size) {
            if (data.size - at < 8) invalid()
            val tag = text(at, 4); val size = buffer.getInt(at + 4)
            if (size < 0 || size > data.size - at - 8) invalid()
            val start = at + 8; val end = start.toLong() + size + size % 2
            if (end > data.size) invalid()
            when (tag) {
                "fmt " -> {
                    if (format || payload != null || size !in listOf(16, 18)) invalid()
                    if (buffer.getShort(start).toInt() != 1 || buffer.getShort(start + 2).toInt() != 1 ||
                        buffer.getInt(start + 4) != SAMPLE_RATE || buffer.getInt(start + 8) != SAMPLE_RATE * 2 ||
                        buffer.getShort(start + 12).toInt() != 2 || buffer.getShort(start + 14).toInt() != 16 ||
                        size == 18 && buffer.getShort(start + 16).toInt() != 0) invalid()
                    format = true
                }
                "data" -> { if (!format || payload != null || size % 2 != 0) invalid(); payload = size }
            }
            at = end.toInt()
        }
        val frames = (payload ?: invalid()) / 2
        if (frames !in SAMPLE_RATE * 3..MAX_FRAMES) throw VoiceprintDurationException()
        return frames * 1000L / SAMPLE_RATE
    }
    fun read(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count == 0 || count > MAX_BYTES - output.size()) throw VoiceprintAudioFormatException()
                output.write(buffer, 0, count)
            }
            return output.toByteArray().also(::duration)
        } finally { buffer.fill(0) }
    }
    fun pcm(data: ByteArray): ShortArray {
        duration(data)
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        while (at < data.size) {
            val size = bytes.getInt(at + 4)
            if (String(data, at, 4, Charsets.US_ASCII) == "data") {
                bytes.position(at + 8)
                return ShortArray(size / 2) { bytes.short }
            }
            at += 8 + size + size % 2
        }
        throw VoiceprintAudioFormatException()
    }
}
