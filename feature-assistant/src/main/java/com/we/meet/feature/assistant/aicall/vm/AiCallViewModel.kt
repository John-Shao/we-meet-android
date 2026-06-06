package com.we.meet.feature.assistant.aicall.vm

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.data.AiAgentRepository
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.data.AiRoomRepository
import com.we.meet.feature.assistant.aicall.data.RoomApi
import com.we.meet.feature.assistant.aicall.model.AiAgentConfigResponse
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus
import com.we.meet.feature.assistant.aicall.model.AiCallUiState
import com.we.meet.feature.assistant.aicall.model.AiModeSelection
import com.we.meet.feature.assistant.aicall.model.AiProfileDto
import com.we.meet.feature.assistant.aicall.model.ConnectingStep
import com.we.meet.feature.assistant.net.AssistantNetwork
import com.we.meet.feature.assistant.util.toUserMessage
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Drives the AI call session end-to-end:
 *  1. POST /api/v1.0/rooms/ to create a LiveKit room
 *  2. Connect to LiveKit, route audio to speaker, publish mic (+ camera if video)
 *  3. POST /start-ai-agent/ with the LiveKit token + provider/voice/prompt
 *  4. Wait for an "ai-agent-*" participant to join (10s timeout)
 *  5. Stream audio levels into the UI for the animated sphere
 *
 * Cleanup runs in an independent SupervisorJob scope so a nav-pop cancellation
 * cannot abort the stop-ai-agent / end-room HTTP calls.
 */
class AiCallViewModel(
    private val appContext: Context,
    private val roomRepo: AiRoomRepository,
    private val agentRepo: AiAgentRepository,
    private val prefs: AiCallPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AiCallUiState(
            voiceSelection = prefs.load(AiCallMode.Voice),
            videoSelection = prefs.load(AiCallMode.Video),
        )
    )
    val state: StateFlow<AiCallUiState> = _state.asStateFlow()

    var liveKitRoom: Room? by mutableStateOf(null)
        private set
    var localVideoTrack: VideoTrack? by mutableStateOf(null)
        private set

    private var connectJob: Job? = null
    private var levelJob: Job? = null
    private var currentRoomId: String? = null
    private var currentLkToken: String? = null

    init {
        loadConfig()
    }

    // region Config

    fun loadConfig() {
        viewModelScope.launch {
            runCatching { agentRepo.fetchConfig() }
                .onSuccess { cfg -> _state.update { it.copy(agentConfig = cfg) } }
                .onFailure { e ->
                    Log.w(TAG, "loadConfig failed", e)
                }
        }
    }

    // endregion

    // region User actions

    /**
     * Unified mode toggle:
     *  - Idle / Failed / Ended: just flip the preferred mode for the next call.
     *  - Active: hot-swap (stop agent → flip camera publish → start agent → wait).
     *  - Connecting: ignored — the right-hand button is disabled in this state.
     */
    fun toggleMode() {
        val cur = _state.value.status
        val nextMode = if (_state.value.mode == AiCallMode.Voice) AiCallMode.Video else AiCallMode.Voice
        when (cur) {
            is AiCallStatus.Idle, is AiCallStatus.Failed, is AiCallStatus.Ended -> {
                _state.update { it.copy(mode = nextMode) }
            }
            is AiCallStatus.Active -> switchModeHot(nextMode)
            is AiCallStatus.Connecting -> Unit
        }
    }

    fun startCall() {
        val cur = _state.value.status
        if (cur is AiCallStatus.Connecting || cur is AiCallStatus.Active) return

        val cfg = _state.value.agentConfig
        if (cfg == null) {
            _state.update { it.copy(errorToast = "AI 配置加载中，请稍后重试") }
            loadConfig()
            return
        }

        connectJob = viewModelScope.launch {
            runCatching { runConnectFlow(cfg) }
                .onFailure { e ->
                    // Outer cancel from endCall() → cleanup handled there.
                    if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) {
                        return@launch
                    }
                    Log.w(TAG, "connect failed", e)
                    cleanupAfterFailure(e.toUserMessage(appContext))
                }
        }
    }

    fun endCall(reason: String? = null) {
        if (_state.value.status is AiCallStatus.Idle) return
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanupScope.launch { endCallAsync(reason) }
    }

    fun toggleMic() {
        val room = liveKitRoom ?: return
        val nextMuted = !_state.value.isMicMuted
        _state.update { it.copy(isMicMuted = nextMuted) }
        viewModelScope.launch {
            runCatching { room.localParticipant.setMicrophoneEnabled(!nextMuted) }
        }
    }

    /** Swap between front and back camera while in an active video call. */
    fun flipCamera() {
        if (_state.value.status !is AiCallStatus.Active) return
        if (_state.value.mode != AiCallMode.Video) return
        val track = localVideoTrack as? LocalVideoTrack ?: return
        val next = if (_state.value.cameraFront) CameraPosition.BACK else CameraPosition.FRONT
        runCatching { track.switchCamera(position = next) }
        _state.update { it.copy(cameraFront = !it.cameraFront) }
    }

    fun onTapToInterrupt() {
        if (_state.value.status !is AiCallStatus.Active) return
        val room = liveKitRoom ?: return
        viewModelScope.launch {
            runCatching {
                room.localParticipant.setMicrophoneEnabled(false)
                delay(150)
                room.localParticipant.setMicrophoneEnabled(!_state.value.isMicMuted)
            }
        }
    }

    fun showPicker(show: Boolean) {
        if (show && _state.value.status !is AiCallStatus.Idle &&
            _state.value.status !is AiCallStatus.Failed &&
            _state.value.status !is AiCallStatus.Ended
        ) return
        _state.update { it.copy(showPicker = show) }
    }

    /**
     * Set the current call mode without triggering a hot-swap. Called from
     * the settings sheet when the user switches tabs (语音通话 / 视频通话)
     * — keeps the tab the user is configuring in lock-step with the mode
     * the next call will run in. Ignored when a call is already active
     * (active call should use [toggleMode] for hot-swap instead).
     */
    fun setMode(mode: AiCallMode) {
        if (_state.value.mode == mode) return
        if (_state.value.status is AiCallStatus.Active ||
            _state.value.status is AiCallStatus.Connecting
        ) return
        _state.update { it.copy(mode = mode) }
    }

    /** Set the agent profile for [mode]. Resets voice (voices belong to a
     *  specific profile), keeps prompt. */
    fun selectProfile(mode: AiCallMode, profileCode: String?) {
        updateSelection(mode) { it.copy(profileCode = profileCode, voiceId = null) }
    }

    /** Set the voice id for [mode]. */
    fun selectVoice(mode: AiCallMode, voiceId: String?) {
        updateSelection(mode) { it.copy(voiceId = voiceId) }
    }

    /** Set the prompt id for [mode]. ``null`` = no prompt (agent uses
     *  built-in behaviour). */
    fun selectPrompt(mode: AiCallMode, promptId: String?) {
        updateSelection(mode) { it.copy(promptId = promptId) }
    }

    private inline fun updateSelection(
        mode: AiCallMode,
        crossinline transform: (AiModeSelection) -> AiModeSelection,
    ) {
        _state.update { state ->
            val cur = state.selectionFor(mode)
            val next = transform(cur)
            prefs.save(mode, next)
            if (mode == AiCallMode.Voice) state.copy(voiceSelection = next)
            else state.copy(videoSelection = next)
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorToast = null) }
    }

    fun consumeEnded() {
        if (_state.value.status is AiCallStatus.Ended || _state.value.status is AiCallStatus.Failed) {
            _state.update { it.copy(status = AiCallStatus.Idle) }
        }
    }

    // endregion

    // region Connect flow

    private suspend fun runConnectFlow(cfg: AiAgentConfigResponse) {
        val mode = _state.value.mode
        val profile = resolveProfile(cfg, mode)
            ?: error("没有可用的 AI 模型配置")
        val profileCode = profile.code
        val voiceId = resolveVoiceId(profile, mode)
        val promptId = resolvePromptId(cfg, mode)

        setStatus(AiCallStatus.Connecting(ConnectingStep.CreatingRoom))
        val room = roomRepo.createRoom("__JUSI_AI_SESSION__-${System.currentTimeMillis()}")
        val lk = room.livekit ?: error("房间未返回 LiveKit 信息")
        currentRoomId = room.id
        currentLkToken = lk.token

        setStatus(AiCallStatus.Connecting(ConnectingStep.JoiningLiveKit))
        val lkRoom = LiveKit.create(
            appContext = appContext,
            options = RoomOptions(
                videoTrackCaptureDefaults = LocalVideoTrackOptions(
                    // Default to BACK camera — AI 视频场景多是「拿手机给 AI
                    // 看东西」, 后置摄像头取景更自然; 用户可在通话中切前置。
                    position = CameraPosition.BACK,
                    // Cap capture at 5 fps — omni 视觉模型只按低频采样, 30 fps
                    // 既浪费端上 CPU/电量, 编码后又会被 LiveKit 丢帧。capture
                    // 和 publish 两边同时设为 5 让流水线整体对齐。
                    captureParams = VideoCaptureParameter(1280, 720, 5),
                ),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = VideoEncoding(maxBitrate = 2_500_000, maxFps = 5),
                    simulcast = false,
                    videoCodec = "h264",
                ),
                adaptiveStream = true,
                dynacast = true,
            ),
        )
        lkRoom.connect(url = lk.url, token = lk.token)
        liveKitRoom = lkRoom
        forceSpeakerphone(lkRoom)
        observeRoomEvents(lkRoom)

        setStatus(AiCallStatus.Connecting(ConnectingStep.PublishingTracks))
        lkRoom.localParticipant.setMicrophoneEnabled(true)
        if (mode == AiCallMode.Video) {
            publishCameraAndAwait(lkRoom)
            _state.update { it.copy(isCameraEnabled = true) }
        }

        setStatus(AiCallStatus.Connecting(ConnectingStep.StartingAgent))
        agentRepo.startAgent(
            roomId = room.id,
            livekitToken = lk.token,
            profileCode = profileCode,
            voiceId = voiceId,
            promptId = promptId,
        )

        setStatus(AiCallStatus.Connecting(ConnectingStep.WaitingAgent))
        awaitAgentJoin(lkRoom, excluded = emptySet(), timeoutMs = 10_000)

        setStatus(AiCallStatus.Active(mode))
        startLevelLoop(lkRoom)
    }

    /**
     * Hot-swap voice ↔ video without rebuilding the LiveKit room. The provider
     * must change (doubao_s2s ↔ qwen) so the AI agent has to be stopped and
     * restarted; the room itself, mic publication and user identity persist.
     */
    private fun switchModeHot(nextMode: AiCallMode) {
        val room = liveKitRoom ?: return
        val roomId = currentRoomId ?: return
        val token = currentLkToken ?: return
        val cfg = _state.value.agentConfig ?: return

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            // Snapshot of existing AI agent identities so the wait-for-rejoin
            // logic can skip the soon-to-be-removed old agent.
            val oldAgentIds = room.remoteParticipants.values
                .mapNotNull { it.identity?.value }
                .filter { it.startsWith("ai-agent") }
                .toSet()

            runCatching {
                setStatus(AiCallStatus.Connecting(ConnectingStep.SwitchingMode))
                levelJob?.cancel(); levelJob = null
                _state.update { it.copy(agentAudioLevel = 0f, agentSpeaking = false) }

                runCatching { agentRepo.stopAgent(roomId, token) }

                if (nextMode == AiCallMode.Video) {
                    publishCameraAndAwait(room)
                    // Re-publishing always starts at the room default (BACK).
                    _state.update { it.copy(isCameraEnabled = true, cameraFront = false) }
                } else {
                    runCatching { room.localParticipant.setCameraEnabled(false) }
                    localVideoTrack = null
                    _state.update { it.copy(isCameraEnabled = false) }
                }

                _state.update { it.copy(mode = nextMode) }

                val profile = resolveProfile(cfg, nextMode)
                    ?: error("没有可用的 AI 模型配置")
                agentRepo.startAgent(
                    roomId = roomId,
                    livekitToken = token,
                    profileCode = profile.code,
                    voiceId = resolveVoiceId(profile, nextMode),
                    promptId = resolvePromptId(cfg, nextMode),
                )

                awaitAgentJoin(room, excluded = oldAgentIds, timeoutMs = 10_000)

                setStatus(AiCallStatus.Active(nextMode))
                startLevelLoop(room)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) {
                    return@launch
                }
                Log.w(TAG, "switchMode failed", e)
                cleanupAfterFailure(e.toUserMessage(appContext))
            }
        }
    }

    private suspend fun awaitAgentJoin(room: Room, excluded: Set<String>, timeoutMs: Long) {
        val already = room.remoteParticipants.values.any {
            val id = it.identity?.value ?: return@any false
            id.startsWith("ai-agent") && id !in excluded
        }
        if (already) return
        val ok = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val job = viewModelScope.launch {
                    room.events.collect { ev ->
                        if (ev is RoomEvent.ParticipantConnected) {
                            val id = ev.participant.identity?.value
                            if (id != null && id.startsWith("ai-agent") && id !in excluded && cont.isActive) {
                                cont.resume(Unit)
                            }
                        }
                    }
                }
                cont.invokeOnCancellation { job.cancel() }
            }
        }
        if (ok == null) error("AI 助手未能加入房间")
    }

    private suspend fun publishCameraAndAwait(room: Room) {
        withTimeout(10_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                lateinit var job: Job
                job = viewModelScope.launch {
                    room.events.events
                        .onSubscription {
                            // Launch as a sibling under viewModelScope so a setCameraEnabled
                            // failure (camera busy, permission yanked mid-flow) doesn't
                            // cancel the collect job and starve the TrackPublished event.
                            viewModelScope.launch {
                                runCatching { room.localParticipant.setCameraEnabled(true) }
                            }
                        }
                        .collect { ev ->
                            if (ev is RoomEvent.TrackPublished &&
                                ev.participant == room.localParticipant &&
                                ev.publication.source == Track.Source.CAMERA
                            ) {
                                localVideoTrack = ev.publication.track as? VideoTrack
                                if (cont.isActive) cont.resume(Unit)
                                job.cancel()
                                return@collect
                            }
                        }
                }
                cont.invokeOnCancellation { job.cancel() }
            }
        }
    }

    private fun forceSpeakerphone(room: Room) {
        runCatching {
            (room.audioHandler as? AudioSwitchHandler)?.let { handler ->
                handler.availableAudioDevices
                    .filterIsInstance<com.twilio.audioswitch.AudioDevice.Speakerphone>()
                    .firstOrNull()
                    ?.let { handler.selectDevice(it) }
            }
        }
    }

    private fun observeRoomEvents(room: Room) {
        viewModelScope.launch {
            room.events.collect { ev ->
                when (ev) {
                    is RoomEvent.Disconnected -> {
                        if (_state.value.status is AiCallStatus.Active) {
                            endCall(reason = "连接已断开，通话已结束")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startLevelLoop(room: Room) {
        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            while (isActive && _state.value.status is AiCallStatus.Active) {
                updateAudioLevel(room)
                delay(33)
            }
        }
    }

    private fun updateAudioLevel(room: Room) {
        val agent = room.remoteParticipants.values.firstOrNull {
            it.identity?.value?.startsWith("ai-agent") == true
        }
        val raw = (agent?.audioLevel ?: 0f).coerceAtLeast(0f)
        val speaking = agent?.isSpeaking == true
        val target = if (speaking) (raw * 2.5f).coerceAtMost(1f) else 0f
        val current = _state.value.agentAudioLevel
        val alpha = if (target > current) 0.4f else 0.08f
        val smoothed = current + (target - current) * alpha
        _state.update { it.copy(agentSpeaking = speaking, agentAudioLevel = smoothed) }
    }

    // endregion

    // region Cleanup

    private suspend fun cleanupAfterFailure(message: String) {
        val roomId = currentRoomId
        val token = currentLkToken
        currentRoomId = null
        currentLkToken = null
        disconnectLiveKit()
        if (!roomId.isNullOrBlank() && !token.isNullOrBlank()) {
            runCatching { agentRepo.stopAgent(roomId, token) }
        }
        if (!roomId.isNullOrBlank()) {
            roomRepo.endRoom(roomId)
        }
        _state.update {
            it.copy(
                status = AiCallStatus.Failed(message),
                isCameraEnabled = false,
                cameraFront = false,
                agentAudioLevel = 0f,
                agentSpeaking = false,
            )
        }
    }

    private suspend fun endCallAsync(reason: String?) {
        // Cancel any in-flight connect first
        connectJob?.let { it.cancel(); it.join() }
        connectJob = null
        levelJob?.cancel(); levelJob = null

        val roomId = currentRoomId
        val token = currentLkToken
        currentRoomId = null
        currentLkToken = null

        _state.update {
            it.copy(
                status = AiCallStatus.Ended,
                isCameraEnabled = false,
                cameraFront = false,
                agentAudioLevel = 0f,
                agentSpeaking = false,
                errorToast = reason ?: it.errorToast,
            )
        }

        withTimeoutOrNull(15_000) {
            disconnectLiveKit()
            if (!roomId.isNullOrBlank() && !token.isNullOrBlank()) {
                runCatching { agentRepo.stopAgent(roomId, token) }
            }
            if (!roomId.isNullOrBlank()) {
                val first = roomRepo.endRoom(roomId)
                if (first.isFailure) {
                    delay(2_000)
                    roomRepo.endRoom(roomId)
                }
            }
        }
    }

    private fun disconnectLiveKit() {
        runCatching {
            liveKitRoom?.disconnect()
            liveKitRoom?.release()
        }
        liveKitRoom = null
        localVideoTrack = null
    }

    override fun onCleared() {
        disconnectLiveKit()
        super.onCleared()
    }

    // endregion

    // region Helpers

    private fun setStatus(status: AiCallStatus) {
        _state.update { it.copy(status = status) }
    }

    /** Resolve the user's chosen profile for [mode]; if their stored
     *  ``profileCode`` no longer exists in the catalog (or they never
     *  picked one), fall back to the backend-declared default. */
    private fun resolveProfile(cfg: AiAgentConfigResponse, mode: AiCallMode): AiProfileDto? {
        val picked = _state.value.selectionFor(mode).profileCode
        val byCode = picked?.let { cfg.profile(it) }
        if (byCode != null) return byCode
        return if (mode == AiCallMode.Video) cfg.videoProfile() else cfg.voiceProfile()
    }

    /** Resolve the voice id for [mode]: user pick (validated against the
     *  resolved profile's voice list) → profile's default voice id. */
    private fun resolveVoiceId(profile: AiProfileDto, mode: AiCallMode): String? {
        val picked = _state.value.selectionFor(mode).voiceId
        if (picked != null && profile.voices.any { it.id == picked }) return picked
        return profile.default_voice_id
            ?: profile.voices.firstOrNull()?.id
    }

    /** Resolve the prompt id for [mode]: user pick (validated against the
     *  catalog) → null (no prompt — agent uses its built-in behaviour). */
    private fun resolvePromptId(cfg: AiAgentConfigResponse, mode: AiCallMode): String? {
        val picked = _state.value.selectionFor(mode).promptId ?: return null
        return cfg.prompts.firstOrNull { it.id == picked }?.id
    }

    // endregion

    /**
     * Builds the VM from host-provided [AssistantDeps] (authenticated OkHttp +
     * base URL) instead of owning an Application/service-locator. The feature
     * constructs its own Retrofit/APIs/repos on top of the host's auth.
     */
    class Factory(
        private val appContext: Context,
        private val deps: AssistantDeps,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AiCallViewModel::class.java))
            val retrofit = AssistantNetwork.retrofit(deps)
            return AiCallViewModel(
                appContext = appContext.applicationContext,
                roomRepo = AiRoomRepository(retrofit.create(RoomApi::class.java)),
                agentRepo = AiAgentRepository(retrofit.create(AiAgentApi::class.java)),
                prefs = AiCallPreferences(appContext),
            ) as T
        }
    }

    private companion object {
        const val TAG = "AiCallVM"
    }
}
