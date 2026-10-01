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
    @Test fun summaryAndTodoSourcesAreReadableAndCheckable() {
        val summary = AssistantSummary("讨论了报告发送计划。", listOf("明天发送报告"),
            listOf(AssistantTodo("发送报告", "我", "明天", listOf("u"))))
        val entry = androidx.compose.runtime.mutableStateOf(AssistantHistoryEntry("test", "call", 1, 2,
            listOf(AssistantHistoryRow("u", 0, "user", "我明天发送报告。")), summary))
        compose.setContent { WeMeetTheme(darkTheme = false) {
            AssistantSummaryPanel(entry.value, null, null) { index, done ->
                entry.value = entry.value.copy(summary = entry.value.summary!!.copy(tasks = entry.value.summary!!.tasks.mapIndexed { i, task -> if (i == index) task.copy(done = done) else task }))
            }
        } }
        compose.onNodeWithText("讨论了报告发送计划。").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(entry.value.summary!!.tasks.single().done) }
        compose.onNodeWithText(context.getString(R.string.assistant_summary_sources)).performClick()
        compose.onNodeWithText("我明天发送报告。", substring = true).assertIsDisplayed()
    }
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun historySearchDetailCopyAndDeleteAreReachable() {
        val account = "history-ui-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val recording = store.begin("translation")!!
        recording.put(AssistantHistoryRow("one", 0, "translation", "Good morning!", "早上好！", "zh", "en"))
        recording.put(AssistantHistoryRow("two", 1, "translation", "谢谢！", "Thank you!", "en", "zh"))
        recording.close()
        compose.waitUntil(5000) { store.entries.value.size == 1 }
        compose.setContent { WeMeetTheme(darkTheme = false) { AssistantHistoryScreen(store, onBack = {}) } }
        compose.onNodeWithText(context.getString(R.string.assistant_history_save)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_search)).performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).performTextInput("不存在")
        compose.onNodeWithText(context.getString(R.string.assistant_history_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_close_search)).performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).assertDoesNotExist()
        compose.onNodeWithText("Good morning!").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_search)).performClick()
        compose.onNodeWithText(context.getString(R.string.assistant_history_search)).performTextReplacement("morning")
        compose.onNodeWithText("Good morning!").performClick()
        compose.onNodeWithText("Good morning!", substring = true).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.assistant_history_copy)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(context.getString(R.string.assistant_history_share)).assertCountEquals(1)
        compose.onNodeWithContentDescription(context.getString(R.string.assistant_history_copy)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text
            assertTrue(copied.contains("早上好"))
            assertTrue(copied.contains("Thank you!"))
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
