package com.we.meet.data.voiceprint

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observe routing metadata only. No AudioRecord, PCM, camera or additional permissions. */
internal class VoiceprintInputChanges(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val mutable = MutableStateFlow(0L)
    val revision = mutable.asStateFlow()
    private var inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.id }.toSet()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }
    private fun changed() {
        val next = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.id }.toSet() }.getOrElse {
            mutable.value++; return
        }
        if (next != inputs) { inputs = next; mutable.value++ }
    }
    private val stopCommunication: (() -> Unit)?
    init {
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        stopCommunication = try {
            if (Build.VERSION.SDK_INT >= 31) VoiceprintCommunicationChanges.attach(context, manager) { mutable.value++ } else null
        } catch (error: Exception) { manager.unregisterAudioDeviceCallback(callback); throw error }
    }
    override fun close() { manager.unregisterAudioDeviceCallback(callback); stopCommunication?.invoke(); inputs = emptySet() }
}
@RequiresApi(31)
private object VoiceprintCommunicationChanges {
    fun attach(context: Context, manager: AudioManager, changed: () -> Unit): () -> Unit {
        var previous = manager.communicationDevice?.id
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.id != previous) { previous = device?.id; changed() }
        }
        manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
        return { manager.removeOnCommunicationDeviceChangedListener(listener) }
    }
}
