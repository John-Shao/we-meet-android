package com.we.meet.feature.assistant.aicall.model

import com.we.meet.feature.assistant.history.AssistantHistoryPhoto
import com.we.meet.feature.assistant.history.AssistantHistoryRow
import org.junit.Assert.*
import org.junit.Test

class CallGoodbyeTest {
    private val row = AssistantHistoryRow("source", 0, "user", "拜拜。")
    private val active = AiCallUiState(transcriptSessionId = "current", status = AiCallStatus.Active(AiCallMode.Voice))

    @Test fun completeGoodbyesAndNaturalAffixesAreRecognized() {
        listOf("再见", "拜拜", "再见了。", "拜拜了！", " 再 见 。 ", "再見了", "再见吧", "拜拜啦",
            "再见了哈", "拜拜咯", "好啦，拜拜", "好的，那就再见吧", "那先这样，拜拜了",
            "我们下次聊，再见", "今天先聊到这里，拜拜", "先这样，拜拜了哦").forEach {
            assertTrue(it, CallGoodbye.matches(it))
        }
    }

    @Test fun negationQuestionsQuotesTranslationAndOtherActionsAreNotGoodbyes() {
        listOf("", "不要说再见，我们继续聊", "别拜拜，还没说完", "再见是什么意思", "拜拜是什么意思？",
            "把再见了翻译成英语", "如果我说拜拜会怎样", "他说了再见，但我们继续聊", "请扮演说拜拜的人",
            "“拜拜”", "\"再见\"", "拜拜？", "再见?", "再见了吗", "我还没说拜拜", "不能再见面了",
            "打开摄像头", "关闭摄像头", "再看看", "再看一下", "先别说话", "好啦拜拜后继续聊",
            "今天聊聊再见这个词", "你好，再见", "拜拜" + "。".repeat(100)).forEach {
            assertFalse(it, CallGoodbye.matches(it))
        }
    }

    @Test fun onlyCurrentActiveVoiceOrVideoCanEnd() {
        assertTrue(CallGoodbye.shouldEndCall(active, "current", row, true))
        assertTrue(CallGoodbye.shouldEndCall(active.copy(status = AiCallStatus.Active(AiCallMode.Video)), "current", row, true))
        assertFalse(CallGoodbye.shouldEndCall(active, "old", row, true))
        assertFalse(CallGoodbye.shouldEndCall(active, "current", row, false))
        for (status in listOf(AiCallStatus.Idle, AiCallStatus.Connecting(ConnectingStep.Configuring), AiCallStatus.Ended, AiCallStatus.Failed("error"))) {
            assertFalse(CallGoodbye.shouldEndCall(active.copy(status = status), "current", row, true))
        }
    }

    @Test fun assistantPartialsAndPhotosCannotEnd() {
        assertFalse(CallGoodbye.shouldEndCall(active, "current", row.copy(role = "assistant"), true))
        assertFalse(CallGoodbye.shouldEndCall(active, "current", row.copy(isStreaming = true), true))
        assertFalse(CallGoodbye.shouldEndCall(active, "current", row.copy(photo = AssistantHistoryPhoto.Memory(byteArrayOf(1))), true))
    }

    @Test fun endingRejectsDuplicateEventsAndOldSessionEventsInNextCall() {
        assertTrue(CallGoodbye.shouldEndCall(active, "current", row, true))
        val ended = active.copy(status = AiCallStatus.Ended, transcriptSessionId = null)
        assertFalse(CallGoodbye.shouldEndCall(ended, "current", row, false))
        val next = active.copy(transcriptSessionId = "next")
        assertFalse(CallGoodbye.shouldEndCall(next, "current", row, true))
        assertFalse(CallGoodbye.shouldEndCall(next, "current", row, false))
    }
}
