package com.we.meet.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.AssistantTranslationRequest
import com.we.meet.data.api.TranslationVoiceConfig
import com.we.meet.ui.ai.BilingualVoices
import org.junit.Assert.*
import org.junit.Test

class AssistantTranslationContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test fun absentCapabilityVersionCannotBeMistakenForWebSocketV2() {
        val config = moshi.adapter(TranslationVoiceConfig::class.java).fromJson(
            """{"model":"legacy","default_voice":"Tina","voices":[{"value":"Tina","label":"Tina"}]}"""
        )!!
        assertEquals(1, config.protocolVersion)
        assertFalse(BilingualVoices.valid(config))
        assertTrue(BilingualVoices.valid(config.copy(model = "future-compatible-model", protocolVersion = 2)))
    }

    @Test fun businessChoicesUseOneVersionedTicketRequest() {
        val request = AssistantTranslationRequest("ja", "fr", "ja", "Ethan")
        val encoded = moshi.adapter(AssistantTranslationRequest::class.java).toJson(request)
        val decoded = moshi.adapter(Map::class.java).fromJson(encoded)!!
        assertEquals(setOf("source_language", "target_language", "fixed_source", "voice", "protocol_version"), decoded.keys)
        assertEquals("ja", decoded["fixed_source"])
        assertEquals("Ethan", decoded["voice"])
        assertEquals(2.0, decoded["protocol_version"])
    }
}
