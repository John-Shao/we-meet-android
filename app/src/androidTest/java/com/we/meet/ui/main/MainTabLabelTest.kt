package com.we.meet.ui.main

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.R
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainTabLabelTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val labels = listOf(R.string.tab_messages, R.string.tab_meeting,
        R.string.work_title, R.string.tab_docs, R.string.tab_apps)

    @Test fun englishLabelsFitCompletelyAtPhoneWidthInBothThemes() {
        val dark = mutableStateOf(false)
        val selected = mutableStateOf(0)
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1f),
            ) {
                WeMeetTheme(darkTheme = dark.value) {
                    Box(Modifier.requiredWidth(360.dp).testTag("test-tab-bar")) {
                        CompactTabBar(labels.map { TabItem(it, Icons.Filled.Apps, Icons.Filled.Apps) {} },
                            selected.value, { selected.value = it })
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            labels.forEachIndexed { index, label ->
                val text = localized.getString(label)
                assertSingleLine(text, allowEllipsis = false)
                compose.onNodeWithTag("main-tab-${MainTab.entries[index].name.lowercase()}").performClick()
                compose.runOnIdle { assertEquals(index, selected.value) }
                assertSingleLine(text, allowEllipsis = false)
            }
            val bitmap = compose.onNodeWithTag("test-tab-bar").captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "tabs-english-${if (isDark) "dark" else "light"}.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun narrowLargeTextStaysOnOneLineAcrossSupportedLanguages() {
        val locale = mutableStateOf("en")
        compose.setContent {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale.value))
            })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                WeMeetTheme(darkTheme = true) {
                    Box(Modifier.requiredWidth(320.dp)) {
                        CompactTabBar(labels.map { TabItem(it, Icons.Filled.Apps, Icons.Filled.Apps) {} }, 0, {})
                    }
                }
            }
        }
        for (language in listOf("en", "zh-CN", "de", "fr", "nl")) {
            compose.runOnIdle { locale.value = language }
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            labels.forEach { assertSingleLine(localized.getString(it), allowEllipsis = true) }
        }
    }

    private fun assertSingleLine(text: String, allowEllipsis: Boolean) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(text, 1, results.size)
        val layout = results.single()
        assertEquals(text, 1, layout.lineCount)
        // didOverflowHeight also flags maxLines truncation for an ellipsized single line.
        // Check the actual line bounds separately from intentional horizontal truncation.
        assertTrue("$text is clipped vertically", layout.getLineBottom(0) <= layout.size.height)
        if (!allowEllipsis) assertFalse("$text must be fully visible", layout.isLineEllipsized(0))
        else assertTrue("$text must fit or ellipsize", !layout.didOverflowWidth || layout.isLineEllipsized(0))
    }
}
