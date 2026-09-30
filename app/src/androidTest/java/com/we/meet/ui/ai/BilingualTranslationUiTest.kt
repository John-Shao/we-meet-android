package com.we.meet.ui.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.ui.theme.WeMeetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilingualTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hubOpensTranslationWithoutOpeningCall() {
        var calls = 0
        var translations = 0
        compose.setContent { WeMeetTheme(darkTheme = false) { AiHubScreen({ calls++ }, { translations++ }) } }
        compose.onNodeWithText(context.getString(R.string.bilingual_title)).performClick()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(1, translations) }
        screenshot("bilingual-hub.png")
    }

    @Test fun translationStartsIdleWithAutomaticDirectionAndOneStartButton() {
        compose.setContent {
            WeMeetTheme(darkTheme = false) {
                BilingualTranslationScreen(context.applicationContext as WeMeetApp) {}
            }
        }
        compose.onNodeWithText(context.getString(R.string.bilingual_pair)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bilingual_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bilingual_idle)).assertIsDisplayed()
        screenshot("bilingual-screen.png")
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
