package com.we.meet.feature.assistant.aicall.vm

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import com.we.meet.feature.assistant.net.AssistantNetwork
import com.we.meet.feature.assistant.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One-to-one calls use direct WebRTC; no meeting room or agent is created. */
class AiCallViewModel(
    private val appContext: Context,
    private val agentRepo: AiAgentRepository,
    private val prefs: AiCallPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(AiCallUiState(selection = prefs.load()))
    val state = _state.asStateFlow()
    var rtcClient: OmniWebRtcClient? by mutableStateOf(null)
        private set
    private var connectJob: Job? = null
    private var cameraJob: Job? = null

    init { loadConfig() }

    fun loadConfig() {
        viewModelScope.launch {
            runCatching { agentRepo.fetchConfig() }
                .onSuccess { config -> _state.update { it.copy(agentConfig = config) } }
        }
    }

    fun startCall() {
        if (_state.value.status is AiCallStatus.Active || _state.value.status is AiCallStatus.Connecting) return
        val config = _state.value.agentConfig
        if (config == null || config.callProfile() == null) {
            _state.update { it.copy(errorToastRes = R.string.assistant_config_loading) }
            loadConfig()
            return
        }
        val selection = config.resolveSelection(_state.value.selection)
        _state.update { it.copy(status = AiCallStatus.Connecting(ConnectingStep.Connecting), isMicMuted = false) }
        val client = OmniWebRtcClient(
            appContext,
            onAudioLevel = { level ->
                _state.update { it.copy(agentAudioLevel = (level * 2.5f).coerceIn(0f, 1f), agentSpeaking = level > 0.01f) }
            },
            onFailure = { endCall(R.string.assistant_disconnected_ended) },
        )
        rtcClient = client
        connectJob = viewModelScope.launch {
            try {
                client.connect { sdp ->
                    _state.update { it.copy(status = AiCallStatus.Connecting(ConnectingStep.Configuring)) }
                    agentRepo.exchangeOffer(AiCallOffer(sdp, config.callProfile()!!.code, selection.voiceId, selection.promptId))
                }
                if (_state.value.mode == AiCallMode.Video) client.setCameraEnabled(true)
                _state.update { it.copy(status = AiCallStatus.Active(it.mode), isCameraEnabled = it.mode == AiCallMode.Video, cameraFront = client.cameraFront) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) return@launch
                if (rtcClient === client) {
                    closeClient()
                    _state.update { it.copy(status = AiCallStatus.Failed(error.toUserMessage(appContext))) }
                }
            }
        }
    }

    fun endCall(@StringRes reasonRes: Int? = null) {
        connectJob?.cancel()
        connectJob = null
        cameraJob?.cancel()
        cameraJob = null
        closeClient()
        _state.update {
            it.copy(status = AiCallStatus.Ended, isCameraEnabled = false, cameraPending = false,
                cameraFront = false, isMicMuted = false, micPending = false,
                agentSpeaking = false, agentAudioLevel = 0f, errorToastRes = reasonRes)
        }
    }

    fun toggleMode() {
        if (_state.value.cameraPending) return
        val next = if (_state.value.mode == AiCallMode.Voice) AiCallMode.Video else AiCallMode.Voice
        when (_state.value.status) {
            is AiCallStatus.Connecting -> Unit
            is AiCallStatus.Active -> {
                val client = rtcClient ?: return
                _state.update { it.copy(cameraPending = true) }
                runCatching { client.setCameraEnabled(next == AiCallMode.Video) }
                    .onSuccess {
                        _state.update { it.copy(mode = next, status = AiCallStatus.Active(next), isCameraEnabled = next == AiCallMode.Video, cameraFront = client.cameraFront) }
                    }
                    .onFailure { _state.update { it.copy(errorToastRes = R.string.assistant_camera_switch_failed) } }
                _state.update { it.copy(cameraPending = false) }
            }
            else -> _state.update { it.copy(mode = next) }
        }
    }

    fun toggleMic() {
        if (_state.value.status !is AiCallStatus.Active) return
        val client = rtcClient ?: return
        val muted = !_state.value.isMicMuted
        runCatching { client.setMicrophoneEnabled(!muted) }
            .onSuccess { _state.update { it.copy(isMicMuted = muted) } }
            .onFailure { _state.update { it.copy(errorToastRes = R.string.assistant_mic_action_failed) } }
    }

    fun flipCamera() {
        if (!_state.value.isCameraEnabled || _state.value.cameraPending) return
        val client = rtcClient ?: return
        _state.update { it.copy(cameraPending = true) }
        cameraJob = viewModelScope.launch {
            try {
                val front = withTimeout(10_000) { client.flipCamera() }
                _state.update { it.copy(cameraFront = front) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                _state.update { it.copy(errorToastRes = R.string.assistant_camera_switch_failed) }
            } finally {
                _state.update { it.copy(cameraPending = false) }
            }
        }
    }

    fun onTapToInterrupt() {
        if (_state.value.status is AiCallStatus.Active) runCatching { rtcClient?.interrupt() }
    }

    fun showPicker(show: Boolean) {
        if (show && (_state.value.status is AiCallStatus.Active || _state.value.status is AiCallStatus.Connecting)) return
        _state.update { it.copy(showPicker = show) }
    }

    fun selectVoice(id: String?) = updateSelection(_state.value.selection.copy(voiceId = id))
    fun selectPrompt(id: String?) = updateSelection(_state.value.selection.copy(promptId = id))
    private fun updateSelection(selection: AiCallSelection) {
        prefs.save(selection)
        _state.update { it.copy(selection = selection) }
    }
    fun dismissError() { _state.update { it.copy(errorToastRes = null) } }
    fun consumeEnded() {
        if (_state.value.status is AiCallStatus.Ended || _state.value.status is AiCallStatus.Failed) {
            _state.update { it.copy(status = AiCallStatus.Idle) }
        }
    }
    private fun closeClient() {
        val client = rtcClient
        rtcClient = null
        client?.close()
    }
    override fun onCleared() {
        closeClient()
        super.onCleared()
    }

    class Factory(private val appContext: Context, private val deps: AssistantDeps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AiCallViewModel::class.java))
            val retrofit = AssistantNetwork.retrofit(deps)
            return AiCallViewModel(appContext.applicationContext,
                AiAgentRepository(retrofit.create(AiAgentApi::class.java)), AiCallPreferences(appContext)) as T
        }
    }
}
