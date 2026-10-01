package com.we.meet.feature.assistant.aicall.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiCallConfigTest {
    @Test fun sceneUsesItsPromptAndPreservesTransportAndVoice() {
        val answer = AiCallAnswer("sdp-answer", "Tina", "catalog-prompt")
        val preset = com.we.meet.feature.assistant.scenes.AssistantScene.PRACTICE
        assertEquals(answer.copy(instructions = preset.instructions), answer.forScene(preset.id))
        assertEquals(answer, answer.forScene(null))
        assertEquals(answer, answer.forScene("removed"))
    }
    @Test fun sceneSurvivesCatalogResolutionAndUnknownSceneFallsBack() {
        val selection = AiCallSelection(voiceId = "ryan-id", sceneId = "practice")
        assertEquals(selection, config.resolveSelection(selection))
        assertNull(config.resolveSelection(selection.copy(sceneId = "removed")).sceneId)
    }
    private val qwen = AiProfileDto(
        code = "custom-qwen-profile",
        agent_type = "video",
        model_code = "aliyun/qwen3.8-omni-flash-realtime",
        voices = listOf(AiVoiceDto("tina-id", "Tina"), AiVoiceDto("ryan-id", "Ryan")),
        default_voice_id = "tina-id",
    )
    private val config = AiAgentConfigResponse(
        profiles = listOf(
            AiProfileDto("doubao", agent_type = "audio", model_code = "volcengine/s2s"),
            AiProfileDto("qwen-old", model_code = "aliyun/qwen3-omni-flash-realtime"),
            qwen,
        ),
        prompts = listOf(AiPromptDto("prompt-id", "Guide")),
    )

    @Test
    fun bothCameraStatesResolveTheSameModelAndPreferences() {
        val selection = AiCallSelection("ryan-id", "prompt-id")
        for (mode in AiCallMode.entries) {
            val state = AiCallUiState(mode = mode, selection = selection)
            assertEquals(qwen, config.callProfile())
            assertEquals(selection, config.resolveSelection(state.selection))
        }
    }

    @Test
    fun oldProviderVoiceAndRemovedPromptFallBackToCurrentDefaults() {
        assertEquals(
            AiCallSelection("tina-id", null),
            config.resolveSelection(AiCallSelection("doubao-voice-id", "removed-prompt")),
        )
    }

    @Test
    fun missing38ModelNeverFallsBackToAnotherProviderOrVersion() {
        val oldCatalog = config.copy(profiles = config.profiles.dropLast(1))
        assertNull(oldCatalog.callProfile())
        assertNull(oldCatalog.resolveSelection(AiCallSelection("ryan-id")).voiceId)
    }

    @Test
    fun removedDefaultVoiceFallsBackToFirstAvailableVoice() {
        val cfg = config.copy(profiles = listOf(qwen.copy(default_voice_id = "removed")))
        assertEquals("tina-id", cfg.resolveSelection(AiCallSelection()).voiceId)
    }
}
