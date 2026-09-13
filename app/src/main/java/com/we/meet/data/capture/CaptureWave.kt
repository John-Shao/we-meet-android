package com.we.meet.data.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class CaptureWaveInfo(val durationMs: Long, val checksum: String, val byteSize: Int)

/** Canonical little-endian PCM16 mono, 16 kHz. The microphone must actually supply this rate. */
object CaptureWave {
    const val SAMPLE_RATE = 16000
    const val CHUNK_FRAMES = 80000
    const val MAX_CHUNKS = 4320
    const val MAX_PENDING_BYTES = 32 * 1024 * 1024
    const val MAX_DURATION_MS = 43200000L

    fun encode(samples: ShortArray): ByteArray {
        require(samples.size in 16..160000 && samples.size % 16 == 0)
        val bytes = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(bytes.capacity() - 8)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bytes.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
        bytes.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 2)
        samples.forEach(bytes::putShort)
        return bytes.array()
    }

    fun inspect(data: ByteArray): CaptureWaveInfo {
        require(data.size in 76..320044 && (data.size - 44) % 32 == 0)
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int, count: Int) = String(data, at, count, Charsets.US_ASCII)
        require(text(0, 4) == "RIFF" && bytes.getInt(4) == data.size - 8 && text(8, 8) == "WAVEfmt ")
        require(bytes.getInt(16) == 16 && bytes.getShort(20).toInt() == 1 && bytes.getShort(22).toInt() == 1)
        require(bytes.getInt(24) == SAMPLE_RATE && bytes.getInt(28) == SAMPLE_RATE * 2)
        require(bytes.getShort(32).toInt() == 2 && bytes.getShort(34).toInt() == 16)
        require(text(36, 4) == "data" && bytes.getInt(40) == data.size - 44)
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        return CaptureWaveInfo((data.size - 44) / 32L, hash, data.size)
    }
}
