package com.we.meet.feature.assistant.aicall.rtc

import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import org.junit.Assert.*
import org.junit.Test

class AoqAudioDiagnosticsTest {
    @Test fun missingMeasurementsRemainUnknownIncludingBeforeFirstFrame() {
        val diagnostics = AoqAudioDiagnostics { 0L }
        assertTrue(diagnostics.snapshot().contains("statsAgeMs=na"))
        assertTrue(diagnostics.snapshot().contains("decodedFrameAgeMs=na"))
        val text = diagnostics.update(AoqStats())!!
        assertTrue(text.contains("rttMs=na"))
        assertTrue(text.contains("audioRxBps=na"))
        diagnostics.frameReceived()
        assertTrue(diagnostics.snapshot().contains("decodedFrameAgeMs=0"))
        assertTrue(diagnostics.snapshot().contains("audiblePlaybackAgeMs=na"))
    }

    @Test fun zeroTrafficIsDifferentFromMissingStatsAndMutableSdkObjectsAreCopied() {
        var time = 100L
        val diagnostics = AoqAudioDiagnostics { time }
        val network = AoqNetworkStats().apply { rtt = 45; recvBitrate = 0; loss = 3 }
        val stats = AoqStats().apply { networkStats = network }
        diagnostics.update(stats)
        network.rtt = 800
        time += 250
        assertTrue(diagnostics.snapshot().contains("rttMs=45"))
        assertTrue(diagnostics.snapshot().contains("rxBps=0"))
        assertTrue(diagnostics.snapshot().contains("statsAgeMs=250"))
        diagnostics.update(AoqStats())
        assertTrue(diagnostics.snapshot().contains("rttMs=na"))
    }

    @Test fun periodicLogsAreBoundedButLatestNetworkIsAvailableForVad() {
        var time = 0L
        val diagnostics = AoqAudioDiagnostics { time }
        assertNotNull(diagnostics.update(AoqStats()))
        time = 4999
        assertNull(diagnostics.update(AoqStats().apply { networkStats = AoqNetworkStats().apply { rtt = 300 } }))
        assertTrue(diagnostics.snapshot().contains("rttMs=300"))
        time = 5000
        assertNotNull(diagnostics.update(AoqStats()))
        time = 25_000
        assertTrue(diagnostics.snapshot().contains("statsAgeMs=20000"))
    }

    @Test fun decodedFramesAndAudiblePlaybackHaveSeparateTimelines() {
        var time = 0L
        val diagnostics = AoqAudioDiagnostics { time }
        diagnostics.frameReceived(); diagnostics.audiblePlayback()
        time = 1000
        diagnostics.frameReceived()
        time = 1200
        val text = diagnostics.snapshot()
        assertTrue(text.contains("decodedFrameAgeMs=200"))
        assertTrue(text.contains("audiblePlaybackAgeMs=1200"))
        assertTrue(text.contains("maxDecodedFrameGapMs=1000"))
        diagnostics.update(AoqStats())
        assertTrue(diagnostics.snapshot().contains("maxDecodedFrameGapMs=0"))
    }

    @Test fun audioCountersSelectOnlyAudioTracksAndNeverIncludeSessionIdentifiers() {
        val stats = AoqStats().apply {
            audioPublishStats = arrayOf(
                AoqAudioPublishStats().apply { trackType = AoqTrackType.AoqTrackTypeVideo; bitrate = 999 },
                AoqAudioPublishStats().apply { bitrate = 16000; bytes = 32000; encodeVolume = 12 })
            audioSubscribeStats = arrayOf(AoqAudioSubscribeStats().apply { bitrate = 24000; bytes = 48000; playVolume = 18 })
        }
        val text = AoqAudioDiagnostics { 100L }.update(stats)!!
        assertTrue(text.contains("audioTxBps=16000")); assertTrue(text.contains("audioRxBytes=48000"))
        assertTrue(text.contains("encodeVolume=12")); assertFalse(text.contains("999"))
    }
}
