package com.we.meet.feature.assistant.aicall.rtc

import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqStats
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqTrackType

/** Scalar metadata only. SDK counters are not one-way latency or a jitter-buffer measurement. */
internal class AoqAudioDiagnostics(private val now: () -> Long) {
    private var statsAt: Long? = null
    private var lastLogAt: Long? = null
    private var statsText = "rttMs=na lossPct=na txBps=na rxBps=na audioTxBps=na audioRxBps=na"
    private var frameAt: Long? = null
    private var audibleAt: Long? = null
    private var maxFrameGapMs = 0L

    @Synchronized fun frameReceived() {
        val time = now()
        frameAt?.let { maxFrameGapMs = maxOf(maxFrameGapMs, time - it) }
        frameAt = time
    }

    @Synchronized fun audiblePlayback() { audibleAt = now() }

    @Synchronized fun update(stats: AoqStats): String? {
        val time = now()
        val network = stats.networkStats
        val upstream = stats.audioPublishStats?.firstOrNull { it.trackType == AoqTrackType.AoqTrackTypeAudio }
        val downstream = stats.audioSubscribeStats?.firstOrNull { it.trackType == AoqTrackType.AoqTrackTypeAudio }
        // Copy values: SDK callback objects may be reused. Null means unavailable, not zero traffic.
        statsText = "rttMs=${network?.rtt ?: "na"} lossPct=${network?.loss ?: "na"} " +
            "txBps=${network?.sendBitrate ?: "na"} rxBps=${network?.recvBitrate ?: "na"} " +
            "audioTxBps=${upstream?.bitrate ?: "na"} audioRxBps=${downstream?.bitrate ?: "na"} " +
            "audioTxBytes=${upstream?.bytes ?: "na"} audioRxBytes=${downstream?.bytes ?: "na"} " +
            "encodeVolume=${upstream?.encodeVolume ?: "na"} playVolume=${downstream?.playVolume ?: "na"}"
        statsAt = time
        if (lastLogAt?.let { time - it < 5000 } == true) return null
        lastLogAt = time
        return snapshot().also { maxFrameGapMs = 0 }
    }

    @Synchronized fun snapshot(): String {
        val time = now()
        fun age(at: Long?) = at?.let { (time - it).coerceAtLeast(0).toString() } ?: "na"
        return "statsAgeMs=${age(statsAt)} $statsText decodedFrameAgeMs=${age(frameAt)} " +
            "audiblePlaybackAgeMs=${age(audibleAt)} maxDecodedFrameGapMs=$maxFrameGapMs"
    }
}
