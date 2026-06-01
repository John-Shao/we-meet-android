package com.we.meet.feature.assistant.aicall.model

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

data class AiCallUiState(
    val status: AiCallStatus = AiCallStatus.Idle,
    val mode: AiCallMode = AiCallMode.Voice,
    val isMicMuted: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val cameraFront: Boolean = true,
    val agentSpeaking: Boolean = false,
    val agentAudioLevel: Float = 0f,
    val agentConfig: AiAgentConfigResponse? = null,
    val selectedVoiceIndex: Int = 0,
    val selectedPromptLabel: String? = null,
    val errorToast: String? = null,
    val showPicker: Boolean = false,
)
