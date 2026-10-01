package com.we.meet.ui.ai

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.history.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantSummaryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun until(test: () -> Boolean) = runBlocking { withTimeout(5000) { while (!test()) delay(10) } }
    private fun store(): AssistantHistoryStore {
        val id = "summary-test-${UUID.randomUUID()}"
        return AssistantHistoryStore.get(instrumentation.targetContext, id) { id }
    }
    private fun source(store: AssistantHistoryStore): String {
        val recording = store.begin("call")!!
        recording.put(AssistantHistoryRow("u", 0, "user", "明天发送报告")); recording.close()
        until { store.entries.value.firstOrNull()?.endedAt != null }
        return recording.id
    }
    private val summary get() = AssistantSummary("报告安排", listOf("明天发送"), listOf(AssistantTodo("发送报告", "我", "明天", listOf("u"))))

    @Test fun duplicateClicksGenerateOnceAndTodoChecksPersistWithoutRegeneration() {
        val store = store(); val id = source(store)
        val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val vm = AssistantSummaryViewModel(store, { true }) { calls.incrementAndGet(); gate.await(); summary }
        val owner = ViewModelStore().apply { put("summary", vm) }
        try {
            main { vm.generate(id); vm.generate(id) }
            until { calls.get() == 1 }; gate.complete(Unit)
            until { store.entries.value.single().summary != null && vm.requests.value.isEmpty() }
            store.setTodo(id, 0, true)
            until { store.entries.value.single().summary!!.tasks.single().done }
            main { vm.generate(id) }
            assertEquals(1, calls.get())
            assertTrue(store.entries.value.single().summary!!.export().contains("[x]"))
        } finally { main { owner.clear() }; store.clear() }
    }

    @Test fun deletionDuringGenerationCannotRestoreConversationOrSummary() {
        val store = store(); val id = source(store)
        val gate = CompletableDeferred<Unit>()
        val vm = AssistantSummaryViewModel(store, { true }) { gate.await(); summary }
        val owner = ViewModelStore().apply { put("summary", vm) }
        try {
            main { vm.generate(id) }
            store.delete(id); until { store.entries.value.isEmpty() }
            gate.complete(Unit); until { vm.requests.value.isEmpty() }
            assertTrue(store.entries.value.isEmpty())
        } finally { main { owner.clear() }; store.clear() }
    }

    @Test fun failedGenerationCanRetryAndActiveCallsCannotGenerate() {
        val store = store(); val id = source(store)
        var fails = true
        val calls = AtomicInteger()
        val vm = AssistantSummaryViewModel(store, { true }) { calls.incrementAndGet(); if (fails) error("offline") else summary }
        val owner = ViewModelStore().apply { put("summary", vm) }
        try {
            main { vm.generate(id) }; until { vm.requests.value[id] == SummaryRequestState.FAILED }
            fails = false
            main { vm.generate(id) }; until { store.entries.value.single().summary != null }
            val active = store.begin("call")!!
            active.put(AssistantHistoryRow("u", 0, "user", "still talking"))
            until { store.entries.value.size == 2 }
            main { vm.generate(active.id) }
            assertEquals(2, calls.get())
            active.close()
        } finally { main { owner.clear() }; store.clear() }
    }
}
