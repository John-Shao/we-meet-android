package com.we.meet.ui.ai

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.*
import com.alibaba.aoq.clientsdk.AoqClientListener
import com.we.meet.feature.assistant.aicall.rtc.AoqPlaybackMode
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise the actual native SDK before credentials or a paid session are needed. */
class AoqSdkStartupTest {
    @Test fun nativePreconnectionConfigurationSucceeds() {
        assumeTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val engine = AoqClientEngine.createEngine(context, AoqCreateConfig().apply {
                workDir = context.filesDir.absolutePath
            }, object : AoqClientListener() {})
            try {
                assertEquals("disable audio", 0, engine.enableSendMediaStream(AoqTrackType.AoqTrackTypeAudio, false))
                assertEquals("disable video", 0, engine.enableSendMediaStream(AoqTrackType.AoqTrackTypeVideo, false))
                assertEquals("encoder", 0, engine.setAudioEncoderConfig(AoqAudioCodecConfig().apply {
                    codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 16000; channel = 1
                }))
                assertEquals("decoder", 0, engine.setAudioDecoderConfig(AoqAudioCodecConfig().apply {
                    codecType = AoqEncoderType.AoqEncoderTypeAudioOpus; sampleRate = 24000; channel = 1
                }))
                assertEquals("frame listener", 0, engine.setAudioFrameObserver(object : AoqClientListener.AoqAudioFrameListener {}))
                assertEquals("playback observer", 0, engine.enableAudioFrameObserver(true,
                    AoqAudioSource.AoqAudioSourcePlayback, AoqAudioObserverConfig().apply {
                        sampleRate = 24000; channels = 1; mode = AoqAudioObserverMode.AoqAudioObserverModeReadOnly
                    }))
            } finally { AoqClientEngine.destroy() }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun explicitSpeakerPreferenceOverridesEarpieceAfterSdkAudioStartup() {
        assumeTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalMode = manager.mode
        val originalSpeaker = manager.isSpeakerphoneOn
        var engine: AoqClientEngine? = null
        try {
            instrumentation.runOnMainSync {
                engine = AoqClientEngine.createEngine(context, AoqCreateConfig().apply {
                    workDir = context.filesDir.absolutePath
                }, object : AoqClientListener() {})
                assertEquals(0, engine!!.startAudioPlayer(AoqAudioPlaybackConfig().apply {
                    channel = 1; isExternal = false; isDefaultSpeaker = true; isVoipMode = AoqPlaybackMode.voip
                }))
                assertEquals(0, engine!!.startAudioCapture(AoqAudioCaptureConfig().apply {
                    channel = 1; isExternal = false
                }))
                // Simulate a device that falls back to its earpiece during SDK startup.
                assertEquals(0, engine!!.enableSpeakerphone(false))
            }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                assertEquals(0, engine!!.enableSpeakerphone(true))
            }
            // AudioDeviceBroker applies route requests asynchronously.
            SystemClock.sleep(500)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!manager.isSpeakerphoneOn && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Call output must use speaker without a headset", manager.isSpeakerphoneOn)
            assertEquals(if (AoqPlaybackMode.voip) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            instrumentation.runOnMainSync {
                engine?.stopAudioCapture()
                engine?.stopAudioPlayer()
                AoqClientEngine.destroy()
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertEquals("Restore pre-call audio mode", originalMode, manager.mode)
        // SDK 1.3.0 leaves its speaker preference set even after returning to normal
        // mode. Reset this global preference so other instrumentation is isolated.
        manager.isSpeakerphoneOn = originalSpeaker
    }
}
