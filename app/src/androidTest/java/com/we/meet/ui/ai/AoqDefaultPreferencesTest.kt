package com.we.meet.ui.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.AssistantTranslationPair
import com.we.meet.feature.assistant.aicall.data.AiCallPreferences
import com.we.meet.feature.assistant.aicall.model.AiCallOffer
import com.we.meet.feature.assistant.aicall.model.AiCallSelection
import com.we.meet.feature.assistant.aicall.model.AiCallTransport
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real preference migration in both Debug and Release target APKs. */
@RunWith(AndroidJUnit4::class)
class AoqDefaultPreferencesTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "aoq-default-test-${UUID.randomUUID()}-"
    private val stores = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            stores.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }

    @After fun cleanup() { stores.forEach { base.deleteSharedPreferences(it) } }

    @Test fun releaseProbeTargetsNonDebugBuilds() {
        if (InstrumentationRegistry.getArguments().getString("targetRelease") == "true") {
            assertFalse(com.we.meet.BuildConfig.DEBUG)
            assertFalse(com.we.meet.feature.assistant.BuildConfig.DEBUG)
        }
    }

    @Test fun newCallAndAllocationDefaultToAoq() {
        assertEquals(AiCallTransport.AOQ, AiCallSelection().transport)
        assertEquals("aoq", AiCallOffer(sdp = "", profile_code = "qwen").transport)
        assertEquals(AiCallTransport.AOQ, AiCallPreferences(context).load().transport)
    }

    @Test fun existingWebRtcCallMigratesWithoutLosingSelections() {
        callStore().edit().putBoolean("unified_selection", true)
            .putString("call_transport", "WebRTC").putString("call_voice_id", "voice")
            .putString("call_prompt_id", "prompt").putString("call_scene_id", "practice").commit()
        assertEquals(AiCallSelection("voice", "prompt", "practice", AiCallTransport.AOQ),
            AiCallPreferences(context).load())
    }

    @Test fun legacySeparateVoiceSettingsMigrateToAoq() {
        callStore().edit().putString("video_voice_id", "legacy-voice")
            .putString("video_prompt_id", "legacy-prompt").commit()
        assertEquals(AiCallSelection("legacy-voice", "legacy-prompt"), AiCallPreferences(context).load())
    }

    @Test fun manualWebRtcChoiceSurvivesSubsequentLoads() {
        val prefs = AiCallPreferences(context)
        val fallback = prefs.load().copy(transport = AiCallTransport.WebRTC)
        prefs.save(fallback)
        repeat(2) { assertEquals(fallback, AiCallPreferences(context).load()) }
    }

    @Test fun newTranslationDefaultsToAoq() {
        assertTrue(BilingualState().directAoq)
        assertTrue(BilingualPreferences(context, "new").load().directAoq)
    }
    @Test fun explicitDirectionPersistsOnlyForAoqAndValidLanguages() {
        val prefs = BilingualPreferences(context, "direction")
        prefs.save(prefs.load().copy(fixedSource = "en"))
        assertEquals("en", prefs.load().fixedSource)
        prefs.save(prefs.load().copy(directAoq = false))
        assertNull(prefs.load().fixedSource)
        prefs.save(prefs.load().copy(directAoq = true, fixedSource = "fr"))
        assertNull(prefs.load().fixedSource)
    }

    @Test fun existingCloudTranslationKeepsLanguagesSoundAndScene() {
        translationStore("legacy").edit().putBoolean("direct-aoq", false)
            .putString("source", "de").putString("target", "ja")
            .putBoolean("sound", false).putString("scene", "business").commit()
        assertEquals(BilingualState(pair = AssistantTranslationPair("de", "ja"),
            sound = false, sceneId = "business", directAoq = true),
            BilingualPreferences(context, "legacy").load())
    }

    @Test fun manualCloudChoicePersistsAndDoesNotAffectOtherAccounts() {
        val prefs = BilingualPreferences(context, "one")
        val fallback = prefs.load().copy(directAoq = false)
        prefs.save(fallback)
        repeat(2) { assertEquals(fallback, BilingualPreferences(context, "one").load()) }
        assertTrue(BilingualPreferences(context, "two").load().directAoq)
    }

    private fun callStore() = context.getSharedPreferences("we_meet_ai_call_prefs", Context.MODE_PRIVATE)
    private fun translationStore(account: String): SharedPreferences {
        val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("bilingual-settings-$key", Context.MODE_PRIVATE)
    }
}
