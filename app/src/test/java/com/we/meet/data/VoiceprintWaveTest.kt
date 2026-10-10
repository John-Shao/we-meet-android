package com.we.meet.data

import com.we.meet.data.VoiceprintFixtures as F
import com.we.meet.data.voiceprint.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceprintWaveTest {
    @Test fun endpointsAre24KhzMonoPcmAndOriginalFramesSurvivePlaybackDecoding() {
        val samples = ShortArray(24000 * 3) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val bytes = VoiceprintWave.encode(samples)
        assertEquals(3000L, VoiceprintWave.duration(bytes)); assertArrayEquals(samples, VoiceprintWave.pcm(bytes))
        assertEquals(10000L, VoiceprintWave.duration(F.wav(10)))
        assertThrows(VoiceprintDurationException::class.java) { VoiceprintWave.encode(ShortArray(71999)) }
        assertThrows(VoiceprintDurationException::class.java) { VoiceprintWave.encode(ShortArray(240001)) }
    }
    @Test fun wrongRateChannelsEncodingAndAdvertisedLengthsAreRejected() {
        for ((at, value) in listOf(24 to 16000, 28 to 44100, 40 to 1, 4 to -1)) {
            val bytes = F.wav(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
            assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.duration(bytes) }
        }
        val channels = F.wav(); channels[22] = 2
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.duration(channels) }
        val encoding = F.wav(); encoding[20] = 3
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.duration(encoding) }
    }
    @Test fun metadataWithOddPaddingDoesNotAlterFramesAndDuplicateDataIncludingEmptyFirstDataFails() {
        val original = F.wav()
        val junk = byteArrayOf(74, 85, 78, 75, 1, 0, 0, 0, 7, 0)
        val metadata = original.copyOfRange(0, 36) + junk + original.copyOfRange(36, original.size)
        ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN).putInt(4, metadata.size - 8)
        assertArrayEquals(VoiceprintWave.pcm(original), VoiceprintWave.pcm(metadata))
        val duplicate = original.copyOfRange(0, 36) + byteArrayOf(100, 97, 116, 97, 0, 0, 0, 0) + original.copyOfRange(36, original.size)
        ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN).putInt(4, duplicate.size - 8)
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.duration(duplicate) }
        val truncated = original + byteArrayOf(1)
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, truncated.size - 8)
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.duration(truncated) }
    }
    @Test fun readsAreBoundedForUnknownLengthAndDoNotLoopOnAStalledStream() {
        assertArrayEquals(F.wav(), VoiceprintWave.read(ByteArrayInputStream(F.wav())))
        var consumed = 0
        val oversized = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { consumed += length; buffer.fill(1, offset, offset + length); return length }
        }
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.read(oversized) }
        assertEquals(VoiceprintWave.MAX_BYTES + 1, consumed)
        val stalled = object : InputStream() { override fun read() = 0; override fun read(buffer: ByteArray, offset: Int, length: Int) = 0 }
        assertThrows(VoiceprintAudioFormatException::class.java) { VoiceprintWave.read(stalled) }
    }
}
