package com.we.meet.ui.ai

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.R
import com.we.meet.feature.assistant.history.*
import com.we.meet.ui.theme.WeMeetTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun historySearchDetailCopyAndDeleteAreReachable() {
        val account = "history-ui-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val recording = store.begin("translation")!!
        recording.put(AssistantHistoryRow("one", 0, "translation", "Good morning!", "早上好！", "zh", "en"))
        recording.close()
        compose.waitUntil(5000) { store.entries.value.size == 1 }
        compose.setContent { WeMeetTheme(darkTheme = false) { AssistantHistoryScreen(store) {} } }
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).performTextInput("不存在")
        compose.onNodeWithText(context.getString(R.string.assistant_history_empty)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).performTextReplacement("morning")
        compose.onNodeWithText("Good morning!").performClick()
        compose.onNodeWithText("Good morning!", substring = true).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.assistant_history_copy))[1].performClick()
        compose.runOnIdle {
            assertTrue(context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.contains("早上好"))
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "assistant-history-detail.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_delete)).performClick()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.waitUntil(5000) { store.entries.value.isEmpty() }
        compose.onNodeWithText(context.getString(R.string.assistant_history_empty)).assertIsDisplayed()
    }
}
