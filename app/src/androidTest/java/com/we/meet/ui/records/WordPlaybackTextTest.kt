package com.we.meet.ui.records

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.dto.PlaybackWordDto
import com.we.meet.ui.theme.WeMeetTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordPlaybackTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun followsWordsAndSeeksWithoutBreakingLongPressSelection() {
        val text = "我们，Hello 我们。"
        val words = listOf(PlaybackWordDto(0, 2, 0, 400), PlaybackWordDto(3, 8, 500, 900), PlaybackWordDto(9, 11, 900, 1500))
        val active = mutableIntStateOf(0)
        val seeks = mutableListOf<Long>()
        var browsed = false
        compose.setContent {
            WeMeetTheme {
                Column(Modifier.width(320.dp).padding(16.dp)) {
                    WordPlaybackText(text, words, active.intValue, "", false, { browsed = true }, { seeks.add(it) })
                }
            }
        }
        val node = compose.onNodeWithText(text)
        fun spanStarts() = node.fetchSemanticsNode().config[SemanticsProperties.Text].first().spanStyles.map { it.start }
        assertEquals(listOf(0), spanStarts())
        compose.runOnIdle { active.intValue = 2 }
        compose.waitForIdle()
        assertEquals(listOf(9), spanStarts())
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val secondWord = layouts.single().getBoundingBox(4).center
        node.performTouchInput { click(secondWord) }
        compose.runOnIdle { assertEquals(listOf(500L), seeks) }
        node.performTouchInput { longClick(secondWord) }
        compose.runOnIdle { assertEquals(listOf(500L), seeks); assertTrue(browsed) }
        compose.runOnIdle { active.intValue = -1 }
        compose.waitForIdle()
        assertTrue(spanStarts().isEmpty())
    }

    @Test fun capturesMixedLanguageHighlight() {
        val text = "我们今天讨论项目进度。Alice will verify the storage permissions."
        compose.setContent {
            WeMeetTheme {
                Column(Modifier.width(360.dp).padding(16.dp)) {
                    WordPlaybackText(text, listOf(PlaybackWordDto(6, 10, 0, 1000)), 0, "", false, {}, {})
                }
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "word-playback-text.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
