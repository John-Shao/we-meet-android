package com.we.meet.ui.ai

import android.media.AudioFormat
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.we.meet.feature.assistant.aicall.rtc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.junit.Assert.*
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

internal interface SyntheticInput {
    suspend fun say(asset: String)
    suspend fun close()
}

internal suspend fun CoroutineScope.syntheticInput(client: OmniCallClient): SyntheticInput {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (client is OmniAoqClient) {
        val engine = client.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(client) as AoqClientEngine
        withContext(Dispatchers.Main) {
            client.setMicrophoneEnabled(false)
            assertEquals(0, engine.addAudioExternalStream("transcript-test", AoqAudioExternalStreamConfig().apply {
                trackType = AoqTrackType.AoqTrackTypeAudio; codecType = AoqEncoderType.AoqEncoderTypeAudioPCM
                sampleRate = 16000; channels = 1; publishVolume = 100; playoutVolume = 0
                maxBufferDuration = 2000; enable3A = false
            }))
        }
        val packets = Channel<ByteArray>(Channel.UNLIMITED)
        val pump = launch {
            while (isActive) {
                val bytes = packets.tryReceive().getOrNull() ?: ByteArray(640)
                val running = withContext(Dispatchers.Main) {
                    val closed = client.javaClass.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(client)
                    if (closed) return@withContext false
                    val code = engine.pushAudioExternalStreamData("transcript-test", AoqAudioFrameData().apply {
                        dataPtr = bytes; dataSize = bytes.size; bytesPerSample = 2; numOfSamples = bytes.size / 2
                        numOfChannels = 1; samplesPerSec = 16000; timeStamp = android.os.SystemClock.elapsedRealtime()
                    })
                    assertEquals("AOQ input rejected: closed=$closed camera=${client.cameraEnabled}", 0, code)
                    true
                }
                if (!running) break
                delay(20)
            }
        }
        return object : SyntheticInput {
            override suspend fun say(asset: String) {
                val pcm = instrumentation.context.assets.open(asset).use { it.readBytes() } + ByteArray(32000)
                for (offset in pcm.indices step 640) {
                    val frame = ByteArray(640); pcm.copyInto(frame, 0, offset, minOf(offset + 640, pcm.size)); packets.send(frame)
                }
            }
            override suspend fun close() { pump.cancelAndJoin(); packets.close() }
        }
    }
    // Replace captured samples inside the actual WebRTC AudioRecord callback; RTP remains real.
    // The test changes no production factory, media code or provider event stream.
    val module = client.javaClass.getDeclaredField("audioModule").apply { isAccessible = true }.get(client) as JavaAudioDeviceModule
    val record = JavaAudioDeviceModule::class.java.getField("audioInput").get(module)
    val callback = record.javaClass.getDeclaredField("audioBufferCallback").apply { isAccessible = true }
    val original = callback.get(record)
    val pcm = BilingualPcmBuffer()
    val captureFrames = AtomicInteger()
    callback.set(record, JavaAudioDeviceModule.AudioBufferCallback { buffer, format, channels, rate, _, _ ->
        check(format == AudioFormat.ENCODING_PCM_16BIT && channels in 1..2 && rate > 0) {
            "Unsupported test capture format=$format channels=$channels rate=$rate"
        }
        if (captureFrames.getAndIncrement() == 0)
            android.util.Log.i("CallTranscriptLive", "testCapture format=$format channels=$channels rate=$rate capacity=${buffer.capacity()}")
        val frames = buffer.capacity() / (2 * channels)
        val sourceFrames = maxOf(1, (frames.toLong() * 16000 / rate).toInt())
        val source = java.nio.ByteBuffer.allocate(sourceFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.fill(source)
        buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame ->
            val sample = source.getShort(minOf(sourceFrames - 1, frame * sourceFrames / frames) * 2)
            repeat(channels) { buffer.putShort(sample) }
        }
        buffer.rewind()
        System.nanoTime()
    })
    return object : SyntheticInput {
        override suspend fun say(asset: String) {
            pcm.append(instrumentation.context.assets.open(asset).use { it.readBytes() } + ByteArray(32000))
        }
        override suspend fun close() { callback.set(record, original); pcm.close() }
    }
}
