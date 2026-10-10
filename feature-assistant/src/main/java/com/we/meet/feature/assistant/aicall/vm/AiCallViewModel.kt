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
import com.we.meet.feature.assistant.background.AssistantForegroundSession
import com.we.meet.feature.assistant.background.AssistantSessionKind
import com.we.meet.feature.assistant.background.AssistantSessionLease
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.*
import com.we.meet.feature.assistant.aicall.rtc.OmniWebRtcClient
import com.we.meet.feature.assistant.aicall.rtc.OmniCallClient
import com.we.meet.feature.assistant.aicall.rtc.OmniAoqClient
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

/** One-to-one calls default to direct AOQ; no meeting room or agent is created. */
class AiCallViewModel(
    private val appContext: Context,
    private val agentRepo: AiAgentRepository,
    private val prefs: AiCallPreferences,
    val history: com.we.meet.feature.assistant.history.AssistantHistoryStore? = null,
) : ViewModel() {
    private var modelLease: com.we.meet.feature.assistant.aicall.data.DirectAILease? = null
    private var recording: com.we.meet.feature.assistant.history.AssistantHistoryStore.Recording? = null
    private val _state = MutableStateFlow(AiCallUiState(selection = prefs.load()))
    val state = _state.asStateFlow()
    var rtcClient: OmniCallClient? by mutableStateOf(null)
        private set
    private var connectJob: Job? = null
    private var cameraJob: Job? = null
    private var foreground: AssistantSessionLease? = null
    private var cameraController: CameraActionController? = null
    private var pageVisible = false

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
        val transcriptSessionId = java.util.UUID.randomUUID().toString()
        recording = history?.begin("call")
        val currentRecording = recording
        _state.update { it.copy(transcriptSessionId = transcriptSessionId, transcriptRows = emptyList(), transcriptTimestamps = emptyMap(), status = AiCallStatus.Connecting(ConnectingStep.Connecting), isMicMuted = false, isOutputMuted = false, cameraResult = null) }
        val makeClient = if (selection.transport == AiCallTransport.AOQ) ::OmniAoqClient else ::OmniWebRtcClient
        lateinit var client: OmniCallClient
        var owner: CameraActionController? = null
        var sessionId: String? = null
        client = makeClient(
            appContext,
            { level ->
                if (rtcClient === client) _state.update { it.copy(agentAudioLevel = (level * 2.5f).coerceIn(0f, 1f), agentSpeaking = level > 0.01f) }
            },
            { if (rtcClient === client) endCall(R.string.assistant_disconnected_ended) },
            { row ->
                _state.update { it.withTranscript(transcriptSessionId, row) }
                if (!row.isStreaming && _state.value.transcriptSessionId == transcriptSessionId) currentRecording?.put(row)
                if (com.we.meet.feature.assistant.BuildConfig.AI_CALL_VOICE_HANGUP &&
                    CallGoodbye.shouldEndCall(_state.value, transcriptSessionId, row, rtcClient === client)) {
                    // A realtime model can answer a goodbye without requesting its tool.
                    // Closing invalidates this session before any duplicate/late event can act.
                    android.util.Log.i("OmniCall", "source=FinalTranscript action=end_call")
                    endCall()
                }
            },
            CameraToolHandler { request ->
                val started = android.os.SystemClock.elapsedRealtime()
                if (rtcClient === client) _state.update { it.copy(cameraResult = null, errorToastRes = null) }
                val action = when (request) { CameraToolRequest.GetState -> "query"; is CameraToolRequest.SetEnabled -> if (request.enabled) "open" else "close" }
                android.util.Log.i("OmniCamera", "source=Voice action=$action actual=${client.cameraEnabled}")
                val result = if (rtcClient !== client || _state.value.status !is AiCallStatus.Active)
                    cameraResult("cancelled", client.cameraEnabled, false)
                else when (request) {
                    CameraToolRequest.GetState -> checkNotNull(owner).query()
                    is CameraToolRequest.SetEnabled -> checkNotNull(owner).requestCameraEnabled(request.enabled, CameraActionSource.Voice)
                }
                if (rtcClient === client) _state.update { it.copy(cameraResult = result) }
                android.util.Log.i("OmniCamera",
                    "source=Voice code=${result.code} actual=${result.enabled} durationMs=${android.os.SystemClock.elapsedRealtime() - started}")
                result
            },
            { failure -> if (rtcClient === client) {
                android.util.Log.w("OmniCamera", "feedbackStage=${failure.stage} result=${failure.result?.code} actual=${client.cameraEnabled}")
                _state.update {
                    if (failure.stage == CameraFeedbackStage.StateSync) it.copy(cameraResult = null, errorToastRes = R.string.assistant_camera_state_sync_failed)
                    else it.copy(cameraResult = failure.result, errorToastRes = if (failure.result == null)
                        R.string.assistant_camera_result_unconfirmed else R.string.assistant_camera_feedback_failed)
                }
            } },
            { if (rtcClient === client) endCall() },
            PhotoToolHandler { question ->
                if (rtcClient !== client || _state.value.status !is AiCallStatus.Active || sessionId == null)
                    cameraResult("cancelled", client.cameraEnabled, false)
                else {
                    _state.update { it.copy(photoPending = true, cameraResult = null) }
                    try {
                        val result = checkNotNull(owner).takePhoto(client::capturePhoto) { image ->
                            agentRepo.photoQa(checkNotNull(sessionId), question, image)
                        }
                        if (rtcClient === client) _state.update { it.copy(cameraResult = result) }
                        result
                    } finally { if (rtcClient === client) _state.update { it.copy(photoPending = false) } }
                }
            },
            selection.vadMode,
        )
        rtcClient = client
        owner = CameraActionController(
            current = { rtcClient === client }, enabled = { client.cameraEnabled }, available = { client.cameraAvailable },
            visible = { pageVisible && !appContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked },
            permissionGranted = { androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            permissionRequested = { request -> if (rtcClient === client) _state.update { it.copy(cameraPermissionRequest = request) } },
            foregroundCamera = { checkNotNull(foreground).camera(it) }, mediaCamera = client::setCameraEnabled,
            applied = { enabled -> if (rtcClient === client) {
                _state.update {
                it.copy(mode = if (enabled) AiCallMode.Video else AiCallMode.Voice,
                    status = AiCallStatus.Active(if (enabled) AiCallMode.Video else AiCallMode.Voice),
                    isCameraEnabled = enabled, cameraFront = client.cameraFront)
            } } },
            pending = { pending -> if (rtcClient === client) _state.update { it.copy(cameraPending = pending) } },
            unsafe = { if (rtcClient === client) endCall(R.string.assistant_camera_device_error) }, result = ::cameraResult,
        )
        cameraController = owner
        connectJob = viewModelScope.launch {
            try {
                foreground = AssistantForegroundSession.start(appContext, AssistantSessionKind.CALL,
                    camera = _state.value.mode == AiCallMode.Video,
                    stopped = { endCall(R.string.assistant_disconnected_ended) })
                client.connect { sdp ->
                    _state.update { it.copy(status = AiCallStatus.Connecting(ConnectingStep.Configuring)) }
                    val answer = agentRepo.exchangeOffer(AiCallOffer(sdp = sdp, profile_code = config.callProfile()!!.code, voice_id = selection.voiceId, prompt_id = selection.promptId, transport = selection.transport.name.lowercase(), photo_qa = true))
                    sessionId = answer.session_lease?.id
                    val lease = agentRepo.track(answer) {
                        viewModelScope.launch {
                            if (rtcClient === client) endCall(R.string.assistant_disconnected_ended)
                        }
                    }
                    if (rtcClient !== client) { lease?.close(); throw CancellationException("Call was stopped") }
                    modelLease = lease
                    answer
                }
                if (_state.value.mode == AiCallMode.Video) withTimeout(10_000) { client.setCameraEnabled(true) }
                if (_state.value.mode == AiCallMode.Video) client.publishCameraState()
                _state.update { it.copy(status = AiCallStatus.Active(it.mode), isCameraEnabled = it.mode == AiCallMode.Video, cameraFront = client.cameraFront) }
                updateControls()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) return@launch
                val reason = when (error) {
                    is AiCallSetupException -> error.stage
                    is retrofit2.HttpException -> "http_${error.code()}"
                    else -> error.javaClass.simpleName
                }
                android.util.Log.w("OmniCall", "${selection.transport} startup failed: $reason")
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
            it.copy(status = AiCallStatus.Ended, isCameraEnabled = false, cameraPending = false, photoPending = false, cameraPermissionRequest = null,
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
                cameraJob = viewModelScope.launch {
                    requestCameraEnabled(next == AiCallMode.Video, CameraActionSource.Button)
                }
            }
            else -> _state.update { it.copy(mode = next) }
        }
    }

    fun setPageVisible(visible: Boolean) { pageVisible = visible }
    fun selectMode(mode: AiCallMode) {
        if (_state.value.status !is AiCallStatus.Active && _state.value.status !is AiCallStatus.Connecting)
            _state.update { it.copy(mode = mode) }
    }
    fun cameraPermissionResult(id: String, granted: Boolean) { cameraController?.permissionResult(id, granted) }
    suspend fun requestCameraEnabled(enabled: Boolean, source: CameraActionSource): CameraActionResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val client = rtcClient
        val result = if (client == null || _state.value.status !is AiCallStatus.Active)
            cameraResult("cancelled", client?.cameraEnabled, false)
        else checkNotNull(cameraController).requestCameraEnabled(enabled, source)
        if (rtcClient === client) _state.update { it.copy(cameraResult = result) }
        // Tool results already carry current state. Update instructions only
        // for a manual change, which the model would otherwise not observe.
        if (rtcClient === client && source == CameraActionSource.Button && result.success && result.changed)
            client?.publishCameraState()
        if (com.we.meet.feature.assistant.BuildConfig.DEBUG) android.util.Log.i("OmniCamera",
            "source=$source code=${result.code} durationMs=${android.os.SystemClock.elapsedRealtime() - started}")
        return result
    }

    private fun cameraResult(code: String, enabled: Boolean?, changed: Boolean): CameraActionResult {
        val resource = when (code) {
            "enabled" -> R.string.assistant_camera_opened
            "disabled" -> R.string.assistant_camera_closed
            "already_enabled" -> R.string.assistant_camera_already_open
            "already_disabled" -> R.string.assistant_camera_already_closed
            "permission_denied" -> R.string.assistant_camera_permission_denied
            "foreground_required" -> R.string.assistant_camera_foreground_required
            "video_unavailable" -> R.string.assistant_camera_video_unavailable
            "timeout" -> R.string.assistant_camera_timeout
            "cancelled" -> R.string.assistant_camera_cancelled
            "photo_answer" -> R.string.assistant_photo_captured
            "photo_failed" -> R.string.assistant_photo_failed
            else -> R.string.assistant_camera_device_error
        }
        return CameraActionResult(code in setOf("enabled", "disabled", "already_enabled", "already_disabled", "photo_answer"), enabled, changed, code, appContext.getString(resource))
    }

    fun toggleMic() {
        if (_state.value.status !is AiCallStatus.Active) return
        val client = rtcClient ?: return
        val muted = !_state.value.isMicMuted
        runCatching { client.setMicrophoneEnabled(!muted) }
            .onSuccess { _state.update { it.copy(isMicMuted = muted) }; updateControls() }
            .onFailure { _state.update { it.copy(errorToastRes = R.string.assistant_mic_action_failed) } }
    }

    fun flipCamera() {
        if (!_state.value.isCameraEnabled || _state.value.cameraPending) return
        val client = rtcClient ?: return
        _state.update { it.copy(cameraPending = true) }
        cameraJob = viewModelScope.launch {
            try {
                val front = checkNotNull(cameraController).flip { withTimeout(10_000) { client.flipCamera() } }
                if (rtcClient === client) _state.update { it.copy(cameraFront = front) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (rtcClient === client) _state.update { it.copy(errorToastRes = R.string.assistant_camera_switch_failed) }
            } finally {
                if (rtcClient === client) _state.update { it.copy(cameraPending = false) }
            }
        }
    }

    fun toggleOutput() {
        if (_state.value.status !is AiCallStatus.Active) return
        val muted = !_state.value.isOutputMuted
        rtcClient?.setOutputMuted(muted)
        _state.update { it.copy(isOutputMuted = muted) }
        updateControls()
    }

    private fun updateControls() {
        val state = _state.value
        foreground?.controls(com.we.meet.feature.assistant.background.AssistantControlState(
            ready = state.status is AiCallStatus.Active, inputPaused = state.isMicMuted, outputMuted = state.isOutputMuted),
            ::toggleMic, ::toggleOutput)
    }

    fun onTapToInterrupt() {
        if (_state.value.status is AiCallStatus.Active) runCatching { rtcClient?.interrupt() }
    }

    fun showPicker(show: Boolean) {
        if (show && (_state.value.status is AiCallStatus.Active || _state.value.status is AiCallStatus.Connecting)) return
        _state.update { it.copy(showPicker = show) }
    }

    fun selectTransport(value: AiCallTransport) = updateSelection(_state.value.selection.copy(transport = value))
    fun selectVadMode(value: AiCallVadMode) = updateSelection(_state.value.selection.copy(vadMode = value))
    fun selectVoice(id: String?) = updateSelection(_state.value.selection.copy(voiceId = id))
    fun selectPrompt(id: String?) = updateSelection(_state.value.selection.copy(promptId = id, sceneId = null))
    fun selectScene(id: String?) {
        if (id != null && com.we.meet.feature.assistant.scenes.AssistantScene.find(id) == null) return
        updateSelection(_state.value.selection.copy(sceneId = id, promptId = null))
    }
    private fun updateSelection(selection: AiCallSelection) {
        if (_state.value.status is AiCallStatus.Active || _state.value.status is AiCallStatus.Connecting) return
        prefs.save(selection)
        _state.update { it.copy(selection = selection) }
    }
    fun dismissError() { _state.update { it.copy(errorToastRes = null) } }
    fun consumeEnded() {
        if (_state.value.status is AiCallStatus.Ended) {
            _state.update { it.copy(status = AiCallStatus.Idle) }
        }
    }
    private fun closeClient() {
        val unfinished = _state.value.transcriptRows.filter { it.isStreaming }
        _state.update { it.copy(transcriptSessionId = null,
            transcriptRows = it.transcriptRows.map { row -> if (row.isStreaming) row.copy(isStreaming = false) else row }) }
        // Preserve received text when hanging up before the provider's final event.
        unfinished.forEach { recording?.put(it.copy(isStreaming = false)) }
        cameraController?.close(); cameraController = null
        modelLease?.close(); modelLease = null
        val client = rtcClient
        rtcClient = null
        try { client?.close() } finally {
            foreground?.close(); foreground = null
            recording?.close(); recording = null
        }
    }
    override fun onCleared() {
        closeClient()
        super.onCleared()
    }

    class Factory(private val appContext: Context, private val deps: AssistantDeps) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AiCallViewModel::class.java))
            val retrofit = AssistantNetwork.retrofit(deps).newBuilder()
                .client(deps.authedOkHttp.newBuilder().followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).cache(null).build()).build()
            return AiCallViewModel(appContext.applicationContext,
                AiAgentRepository(retrofit.create(AiAgentApi::class.java)), AiCallPreferences(appContext),
                deps.assistantAccount?.let { account -> com.we.meet.feature.assistant.history.AssistantHistoryStore.get(appContext, account) { deps.assistantAccount } }) as T
        }
    }
}
