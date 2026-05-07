package com.we.meet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import livekit.org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "CallADM"

/**
 * Thin wrapper that owns the [JavaAudioDeviceModule] LiveKit uses for audio
 * I/O, and exposes a back-door to pin the WebRTC playback `AudioTrack` to a
 * specific output device via [AudioTrack.setPreferredDevice].
 *
 * Why we need this back-door: on Android 15/16 (and several OEM ROMs),
 * `AudioManager.setCommunicationDevice` only decides the routing of *new*
 * AudioTracks — once WebRTC's playback AudioTrack is running, that track
 * stays on whichever output it started on. Calling `setPreferredDevice()`
 * on the live `AudioTrack` instance *does* hot-reroute it, so we plumb that
 * through.
 *
 * Reflection is used because `WebRtcAudioTrack.audioTrack` is a private
 * field of the bundled WebRTC class; the public `JavaAudioDeviceModule`
 * surface doesn't expose it. If WebRTC changes the field name in a future
 * release we fall back gracefully — the user still has
 * `setCommunicationDevice` in play. See `proguard-rules.pro` for the
 * corresponding R8 keep rule.
 */
class CallAudioDeviceModule(context: Context) {

    /**
     * The user's last-requested device pin. Remembered across AudioTrack
     * re-creations so that if the user toggles speaker/earpiece *before*
     * WebRTC has built its playback track (e.g. before any remote audio has
     * arrived), the choice is applied as soon as the track starts.
     */
    @Volatile
    private var desiredDevice: AudioDeviceInfo? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Fires when WebRTC's playback track actually starts / stops. On start
     * we re-apply the last-requested preferred device; that closes the
     * window where a toggle happens before the track exists.
     */
    private val trackStateCallback = object : JavaAudioDeviceModule.AudioTrackStateCallback {
        override fun onWebRtcAudioTrackStart() {
            val device = desiredDevice ?: return
            Log.i(TAG, "AudioTrack started — re-applying preferred device type=${device.type}")
            applyPreferredDevice(device)
        }

        override fun onWebRtcAudioTrackStop() {
            Log.i(TAG, "AudioTrack stopped")
        }
    }

    val module: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
        // Match LiveKit's own defaults for VoIP:
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioAttributes(attrs)
        .setUseHardwareAcousticEchoCanceler(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        .setUseHardwareNoiseSuppressor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        // Disable LOW_LATENCY so the AudioTrack honors system routing changes
        // (LOW_LATENCY tracks get pinned to the fast-mixer speaker path).
        .setUseLowLatency(false)
        .setAudioTrackStateCallback(trackStateCallback)
        .createAudioDeviceModule()

    /**
     * Pin WebRTC's playback AudioTrack to the given [device]. Pass `null` to
     * drop the pin and let the system route per audio-policy rules (i.e.
     * whatever `setCommunicationDevice` currently says).
     *
     * The choice is remembered; if WebRTC hasn't built its AudioTrack yet
     * (e.g. the user toggled before any remote audio arrived), the pin will
     * be applied once the track starts — see [trackStateCallback].
     *
     * Returns true if the underlying `AudioTrack.setPreferredDevice` call
     * succeeded right now; false if the track isn't built yet (deferred to
     * the state callback) or the call itself rejected the device.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?): Boolean {
        desiredDevice = device
        return applyPreferredDevice(device)
    }

    fun release() {
        runCatching { module.release() }
    }

    private fun applyPreferredDevice(device: AudioDeviceInfo?): Boolean {
        val audioTrack = reflectAudioTrack()
        if (audioTrack == null) {
            Log.w(TAG, "applyPreferredDevice: AudioTrack not initialized yet — deferred")
            return false
        }
        return try {
            val ok = audioTrack.setPreferredDevice(device)
            Log.i(
                TAG,
                "setPreferredDevice(${device?.productName}/type=${device?.type}) -> $ok",
            )
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "setPreferredDevice threw", t)
            false
        }
    }

    private fun reflectAudioTrack(): AudioTrack? {
        return try {
            // `module.audioOutput` is a `livekit.org.webrtc.audio.WebRtcAudioTrack`
            // instance — the class itself is package-private so we can't name
            // it here; we reach into it purely via reflection.
            val webRtcAudioTrack: Any = module.audioOutput
            val field = webRtcAudioTrack.javaClass
                .getDeclaredField("audioTrack")
                .apply { isAccessible = true }
            field.get(webRtcAudioTrack) as? AudioTrack
        } catch (t: Throwable) {
            Log.e(TAG, "reflect audioTrack failed", t)
            null
        }
    }
}
