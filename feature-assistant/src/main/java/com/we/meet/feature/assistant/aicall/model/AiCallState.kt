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
    val isCameraEnabled: Boolean = false,
    val cameraFront: Boolean = true,
    val agentSpeaking: Boolean = false,
    val agentAudioLevel: Float = 0f,
    val agentConfig: AiAgentConfigResponse? = null,
    val voiceSelection: AiModeSelection = AiModeSelection(),
    val videoSelection: AiModeSelection = AiModeSelection(),
    val errorToast: String? = null,
    val showPicker: Boolean = false,
) {
    fun selectionFor(mode: AiCallMode): AiModeSelection =
        if (mode == AiCallMode.Voice) voiceSelection else videoSelection
}
