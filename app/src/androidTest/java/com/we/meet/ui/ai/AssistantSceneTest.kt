package com.we.meet.ui.ai

import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.*
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.scenes.AssistantScene
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AssistantSceneTest {
    @Test fun connectingTranslationKeepsItsSelectedSceneAndLanguage() {
        val controller = BilingualTranslationController(context, api, { true },
            openForeground = { kotlinx.coroutines.awaitCancellation() })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                controller.selectScene("travel_ja")
                controller.start()
                assertEquals(BilingualPhase.CONNECTING, controller.state.value.phase)
                controller.selectScene("business")
                controller.selectLanguage(false, "en")
                assertEquals("travel_ja", controller.state.value.sceneId)
                assertEquals("ja", controller.state.value.pair.target)
            } finally { controller.close() }
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val api = object : AssistantTranslationApi {
        override suspend fun ticket(pair: AssistantTranslationPair): AssistantTranslationTicket = error("No provider calls")
    }

    @Test fun translationPresetAppliesAtomicallyPersistsAndAllowsCustomLanguages() {
        val account = "scene-${UUID.randomUUID()}"
        val prefs = BilingualPreferences(context, account)
        val controller = BilingualTranslationController(context, api, { true }, preferences = prefs)
        try {
            controller.sound(false)
            controller.selectScene("travel_ja")
            assertEquals(AssistantTranslationPair("zh", "ja"), controller.state.value.pair)
            assertTrue(controller.state.value.sound)
            assertEquals("travel_ja", BilingualPreferences(context, account).load().sceneId)
            controller.selectScene("practice") // A tutor must not be offered as a translator.
            assertEquals("travel_ja", controller.state.value.sceneId)
            controller.selectLanguage(false, "fr")
            assertNull(controller.state.value.sceneId)
            assertEquals("fr", BilingualPreferences(context, account).load().pair.target)
            controller.selectScene("business")
            controller.sound(false)
            assertNull(controller.state.value.sceneId)
            assertFalse(BilingualPreferences(context, account).load().sound)
            assertEquals(BilingualState(), BilingualPreferences(context, "other-${UUID.randomUUID()}").load())
        } finally { controller.close() }
    }

    @Test fun callPresetPersistsAlongsideVoiceAndCustomPrompt() {
        val prefs = AiCallPreferences(context)
        val original = prefs.load()
        try {
            val selection = AiCallSelection(voiceId = "test-voice", sceneId = "practice")
            prefs.save(selection)
            assertEquals(selection, AiCallPreferences(context).load())
            val custom = selection.copy(sceneId = null, promptId = "custom-prompt")
            prefs.save(custom)
            assertEquals(custom, AiCallPreferences(context).load())
            assertFalse(AssistantScene.PRACTICE.translation)
        } finally { prefs.save(original) }
    }
}
