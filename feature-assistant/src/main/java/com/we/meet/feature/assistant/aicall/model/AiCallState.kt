package com.we.meet.feature.assistant.aicall.model

import androidx.annotation.StringRes

enum class AiCallMode { Voice, Video }

enum class ConnectingStep {
    CreatingRoom,
    JoiningLiveKit,
    PublishingTracks,
    StartingAgent,
    WaitingAgent,
    SwitchingMode,
}

sealed interface AiCallStatus {
    data object Idle : AiCallStatus
    data class Connecting(val step: ConnectingStep) : AiCallStatus
    data class Active(val mode: AiCallMode) : AiCallStatus
    data class Failed(val message: String) : AiCallStatus
    data object Ended : AiCallStatus
}

/**
 * Per-mode (voice / video) user selection of (profile, voice, prompt).
 * All three are tracked by stable backend ids:
 *  - ``profileCode`` ∈ [AiProfileDto.code]
 *  - ``voiceId`` ∈ AiVoiceDto.id (UUID); null → use profile's default voice
 *  - ``promptId`` ∈ AiPromptDto.id (UUID); null → no prompt
 *
 * When the user hasn't picked anything yet, the ViewModel falls back to
 * [AiAgentConfigResponse.voiceProfile] / [AiAgentConfigResponse.videoProfile]
 * for the profile and to that profile's [AiProfileDto.default_voice_id]
 * for the voice.
 */
data class AiModeSelection(
    val profileCode: String? = null,
    val voiceId: String? = null,
    val promptId: String? = null,
)

data class AiCallUiState(
    val status: AiCallStatus = AiCallStatus.Idle,
    val mode: AiCallMode = AiCallMode.Voice,
    val isMicMuted: Boolean = false,
    val micPending: Boolean = false,
    val isCameraEnabled: Boolean = false,
    // AI 视频通话默认用后置：场景多是「给 AI 看东西」(屏幕/物体/文字),
    // 而不是自拍式的「让 AI 看到我」。用户随时可在通话中切换前置。
    val cameraFront: Boolean = false,
    val agentSpeaking: Boolean = false,
    val agentAudioLevel: Float = 0f,
    val agentConfig: AiAgentConfigResponse? = null,
    val voiceSelection: AiModeSelection = AiModeSelection(),
    val videoSelection: AiModeSelection = AiModeSelection(),
    /**
     * 待弹的提示,存的是 string 资源 id 而不是已解析的文案。
     *
     * [AiCallViewModel] 是普通 ViewModel(拿不到 Context),文案必须由 UI 侧
     * 用 `stringResource` 解析 —— 这样也才能跟随系统语言切换,VM 里写死中文
     * 的话英文界面下就露馅了。
     */
    @StringRes val errorToastRes: Int? = null,
    val showPicker: Boolean = false,
) {
    fun selectionFor(mode: AiCallMode): AiModeSelection =
        if (mode == AiCallMode.Voice) voiceSelection else videoSelection
}
