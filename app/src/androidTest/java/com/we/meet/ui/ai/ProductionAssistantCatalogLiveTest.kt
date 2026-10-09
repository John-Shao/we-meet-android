package com.we.meet.ui.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.WeMeetApp
import com.we.meet.feature.assistant.aicall.data.AiAgentApi
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAssistantCatalogLiveTest {
    @Test fun productionCatalogsExposeManagedVoicesAndOnlySelectableScenes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackend") == "true")
        val app = instrumentation.targetContext.applicationContext as WeMeetApp
        val voices = app.apiClient.assistantTranslationApi.voiceConfig()
        assertEquals("qwen3.8-livetranslate-flash-realtime", voices.model)
        assertEquals("Tina", voices.defaultVoice)
        assertEquals(47, voices.voices.size)
        assertTrue(voices.voices.any { it.value == "Ethan" })
        assertFalse(voices.voices.any { it.value == "Zane" })
        assertTrue(voices.voices.all { it.label.isNotBlank() })
        val api = retrofit2.Retrofit.Builder().baseUrl(app.baseUrl).client(app.authedOkHttp)
            .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
            .build().create(AiAgentApi::class.java)
        val config = api.fetchConfig()
        assertEquals(setOf("call.scene.travel", "call.scene.travel_ja", "call.scene.business", "call.scene.practice"), config.prompts.map { it.code }.toSet())
        assertTrue(config.prompts.all { !it.content.isNullOrBlank() })
        val migrated = config.resolveSelection(AiCallSelection(sceneId = "practice"))
        assertEquals(config.prompts.single { it.code == "call.scene.practice" }.id, migrated.promptId)
        assertNull(migrated.sceneId)
    }
}
