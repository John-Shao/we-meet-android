package com.we.meet.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

enum class AudioOutput { Speaker, Earpiece, Mute }

private const val TAG = "AudioOutputCtrl"

/**
 * Drives call audio routing (Speaker / Earpiece / Mute) via [AudioManager] directly.
 *
 * History — we do not use LiveKit's [io.livekit.android.audio.AudioSwitchHandler]
 * for routing: under the hood it calls `audioManager.isSpeakerphoneOn = true/false`,
 * which on API 31+ is internally translated by the platform into
 * `clearCommunicationDevice()` when called with `false`. That silently undoes any
 * `setCommunicationDevice(BUILTIN_EARPIECE)` call we make, so
 * `AudioSwitchHandler.selectDevice(Earpiece)` ends up being a no-op on
 * modern devices. We install a no-routing [CallFocusAudioHandler]
 * in place of the default `AudioSwitchHandler` and drive routing ourselves from
 * this class via [AudioManager.setCommunicationDevice] on S+.
 */
class AudioOutputController(
    context: Context,
    private val muteOutput: ((Boolean) -> Unit)? = null,
    /**
     * Optional hook to pin the live WebRTC playback AudioTrack to a specific
     * output device. Called after [AudioManager.setCommunicationDevice],
     * because on Android 15/16 an already-playing AudioTrack is not
     * re-routed by `setCommunicationDevice` alone — only
     * `AudioTrack.setPreferredDevice` hot-reroutes it.
     *
     * Pass null from Preview (no active track).
     */
    private val pinPreferredDevice: ((AudioDeviceInfo?) -> Unit)? = null,
) {

    private val audioManager = checkNotNull(
        context.applicationContext.getSystemService(AudioManager::class.java)
    ) { "AudioManager unavailable" }

    private val originalAudioMode = audioManager.mode

    @Suppress("DEPRECATION")
    private val originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
    private val originalVoiceCallMuted = audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)

    private var appliedOutput: AudioOutput? = null
    private var lastAudibleOutput = AudioOutput.Speaker
    private var started = false

    private val listenerExecutor = Executors.newSingleThreadExecutor()
    private val deviceChangedListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                Log.i(
                    TAG,
                    "onCommunicationDeviceChanged: device=${device?.describe()} " +
                        "(appliedOutput=$appliedOutput)",
                )
                // If the system changed the communication device out from
                // under us (e.g. because a silent audio track finished and
                // a new one started), force our last-known-good route back.
                appliedOutput?.takeIf { it != AudioOutput.Mute }?.let { desired ->
                    val matches = when (desired) {
                        AudioOutput.Speaker -> device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        AudioOutput.Earpiece -> device?.type in earpiecePreferredTypes
                        AudioOutput.Mute -> true
                    }
                    if (!matches) {
                        Log.w(
                            TAG,
                            "system-reset of comm device detected — reapplying $desired",
                        )
                        when (desired) {
                            AudioOutput.Speaker -> routeToSpeaker()
                            AudioOutput.Earpiece -> routeToEarpiece()
                            AudioOutput.Mute -> Unit
                        }
                    }
                }
            }
        } else null

    fun start() {
        if (started) return
        // Ensure we're in voice-call mode. On API 31+ this is what makes
        // BUILTIN_EARPIECE appear in `availableCommunicationDevices`, and
        // on pre-31 it's what makes the deprecated `isSpeakerphoneOn`
        // fallback actually route to the call path.
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            deviceChangedListener?.let { l ->
                runCatching {
                    audioManager.addOnCommunicationDeviceChangedListener(listenerExecutor, l)
                }
            }
        }

        Log.i(TAG, "start(): ${summary()}")
        started = true
    }

    fun apply(output: AudioOutput) {
        start()
        if (appliedOutput == output) {
            Log.i(TAG, "apply($output): already applied, skipping")
            return
        }
        Log.i(TAG, "apply($output): begin — ${summary()}")

        when (output) {
            AudioOutput.Speaker -> {
                routeToSpeaker()
                applyMutedState(false)
                lastAudibleOutput = AudioOutput.Speaker
            }
            AudioOutput.Earpiece -> {
                routeToEarpiece()
                applyMutedState(false)
                lastAudibleOutput = AudioOutput.Earpiece
            }
            AudioOutput.Mute -> {
                // Keep whatever route is currently active; just silence the
                // output. If there's no route yet (first apply of the
                // session), set one so unmuting later has somewhere to go.
                if (appliedOutput == null) routeToLastAudibleOutput()
                applyMutedState(true)
            }
        }
        appliedOutput = output
        Log.i(TAG, "apply($output): end — ${summary()}")
    }

    fun stop() {
        if (!started) return
        runCatching {
            if (muteOutput == null) {
                setSystemMute(originalVoiceCallMuted)
            } else {
                muteOutput.invoke(false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            deviceChangedListener?.let {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
            }
            runCatching { audioManager.clearCommunicationDevice() }
        }
        // Drop any AudioTrack device pin so the next session routes cleanly.
        runCatching { pinPreferredDevice?.invoke(null) }
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = originalSpeakerphoneOn }
        runCatching { audioManager.mode = originalAudioMode }

        appliedOutput = null
        lastAudibleOutput = AudioOutput.Speaker
        started = false
        Log.i(TAG, "stop(): restored")
    }

    // ── Routing ───────────────────────────────────────────────────────────

    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val device = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (device != null) {
                val ok = runCatching { audioManager.setCommunicationDevice(device) }
                    .onFailure { Log.e(TAG, "setCommunicationDevice(SPEAKER) threw", it) }
                    .getOrDefault(false)
                val verified = audioManager.communicationDevice
                Log.i(
                    TAG,
                    "routeToSpeaker: setCommunicationDevice(${device.describe()}) -> $ok; " +
                        "verified=${verified?.describe()}",
                )
                // Hot-reroute the live WebRTC AudioTrack — needed on Android
                // 15+ where setCommunicationDevice only affects new streams.
                pinPreferredDevice?.invoke(device)
                if (ok) return
            } else {
                Log.w(
                    TAG,
                    "routeToSpeaker: BUILTIN_SPEAKER not in availableCommunicationDevices " +
                        "(have=${devices.joinToString { it.describe() }})",
                )
            }
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
        Log.i(TAG, "routeToSpeaker: fallback isSpeakerphoneOn=true")
    }

    /**
     * "Earpiece" semantically means "private listening" — prefer Bluetooth or
     * wired headset when present, fall back to the built-in earpiece.
     */
    private fun routeToEarpiece() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            for (type in earpiecePreferredTypes) {
                val device = devices.firstOrNull { it.type == type } ?: continue
                val ok = runCatching { audioManager.setCommunicationDevice(device) }
                    .onFailure { Log.e(TAG, "setCommunicationDevice(type=$type) threw", it) }
                    .getOrDefault(false)
                val verified = audioManager.communicationDevice
                Log.i(
                    TAG,
                    "routeToEarpiece: setCommunicationDevice(${device.describe()}) -> $ok; " +
                        "verified=${verified?.describe()}",
                )
                // Hot-reroute the live WebRTC AudioTrack.
                pinPreferredDevice?.invoke(device)
                if (ok) return
            }
            Log.w(
                TAG,
                "routeToEarpiece: no matching device in availableCommunicationDevices " +
                    "(have=${devices.joinToString { it.describe() }})",
            )
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        Log.i(TAG, "routeToEarpiece: fallback isSpeakerphoneOn=false")
    }

    private fun routeToLastAudibleOutput() {
        when (lastAudibleOutput) {
            AudioOutput.Speaker -> routeToSpeaker()
            AudioOutput.Earpiece -> routeToEarpiece()
            AudioOutput.Mute -> routeToSpeaker()
        }
    }

    // ── Mute ──────────────────────────────────────────────────────────────

    private fun applyMutedState(muted: Boolean) {
        if (muteOutput != null) {
            muteOutput.invoke(muted)
        } else {
            setSystemMute(muted)
        }
    }

    private fun setSystemMute(muted: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.setStreamMute(AudioManager.STREAM_VOICE_CALL, muted) }
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    private fun summary(): String {
        val mode = audioManager.mode
        @Suppress("DEPRECATION")
        val spk = audioManager.isSpeakerphoneOn
        val commDev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.describe() ?: "null"
        } else "n/a"
        val avail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.joinToString { it.describe() }
        } else "n/a"
        return "mode=$mode spk=$spk commDev=$commDev avail=[$avail]"
    }

    private fun AudioDeviceInfo.describe(): String = "${typeName(type)}#$id"

    companion object {
        private val earpiecePreferredTypes = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )

        private fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            else -> "TYPE($type)"
        }
    }
}
