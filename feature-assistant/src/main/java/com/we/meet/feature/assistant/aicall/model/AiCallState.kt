package com.we.meet.feature.assistant.aicall.model

import androidx.annotation.StringRes

enum class AiCallTransport { WebRTC, AOQ }

enum class AiCallMode { Voice, Video }

enum class ConnectingStep {
    Connecting,
    Configuring,

}

sealed interface AiCallStatus {
    data object Idle : AiCallStatus
    data class Connecting(val step: ConnectingStep) : AiCallStatus
    data class Active(val mode: AiCallMode) : AiCallStatus
    data class Failed(val message: String) : AiCallStatus
    data object Ended : AiCallStatus
}

/** Shared voice and prompt preferences for calls with or without a camera. */
data class AiCallSelection(
    val voiceId: String? = null,
    val promptId: String? = null,
    val sceneId: String? = null,
    val transport: AiCallTransport = AiCallTransport.AOQ,
)

data class AiCallUiState(
    val status: AiCallStatus = AiCallStatus.Idle,
    val mode: AiCallMode = AiCallMode.Voice,
    val isMicMuted: Boolean = false,
    val isOutputMuted: Boolean = false,
    val micPending: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val cameraPending: Boolean = false,
    // AI 视频通话默认用后置：场景多是「给 AI 看东西」(屏幕/物体/文字),
    // 而不是自拍式的「让 AI 看到我」。用户随时可在通话中切换前置。
    val cameraFront: Boolean = false,
    val agentSpeaking: Boolean = false,
    val agentAudioLevel: Float = 0f,
    val agentConfig: AiAgentConfigResponse? = null,
    val selection: AiCallSelection = AiCallSelection(),
    /**
     * 待弹的提示,存的是 string 资源 id 而不是已解析的文案。
     *
     * [AiCallViewModel] 是普通 ViewModel(拿不到 Context),文案必须由 UI 侧
     * 用 `stringResource` 解析 —— 这样也才能跟随系统语言切换,VM 里写死中文
     * 的话英文界面下就露馅了。
     */
    @StringRes val errorToastRes: Int? = null,
    val showPicker: Boolean = false,
)
