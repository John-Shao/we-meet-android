package com.we.meet.ui.ai

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.history.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantHistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(5000) { while (!condition()) delay(10) } }

    private fun preferences(account: String): android.content.SharedPreferences {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("assistant-history-$hash", android.content.Context.MODE_PRIVATE)
    }

    @Test fun savingPreferencesMigrateLegacyChoiceWithoutOverwritingSeparateChoices() {
        for (legacy in listOf(null, false, true)) {
            val account = "history-migration-${UUID.randomUUID()}"
            val prefs = preferences(account)
            if (legacy != null) assertTrue(prefs.edit().putBoolean("enabled", legacy).commit())
            val store = AssistantHistoryStore.get(context, account) { account }
            val expected = legacy ?: true
            for (kind in listOf("call", "translation")) {
                assertEquals(expected, store.enabled(kind).value)
                assertTrue(prefs.contains("enabled_$kind"))
                assertEquals(expected, prefs.getBoolean("enabled_$kind", !expected))
            }
        }
        val account = "history-partial-migration-${UUID.randomUUID()}"
        val prefs = preferences(account)
        assertTrue(prefs.edit().putBoolean("enabled", false).putBoolean("enabled_call", true).commit())
        val store = AssistantHistoryStore.get(context, account) { account }
        assertTrue(store.enabled("call").value)
        assertFalse(store.enabled("translation").value)
        assertTrue(prefs.getBoolean("enabled_call", false))
        assertFalse(prefs.getBoolean("enabled_translation", true))
    }

    @Test fun savingChoicesAreIndependentAndOnlyAffectNewSessions() {
        val account = "history-independent-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val call = store.begin("call")!!
        call.put(AssistantHistoryRow("call", 0, "user", "existing call")); call.close()
        waitFor { store.entries.value.singleOrNull()?.endedAt != null }

        store.setEnabled("call", false)
        assertNull(store.begin("call"))
        assertTrue(store.enabled("translation").value)
        val translation = store.begin("translation")!!
        store.setEnabled("translation", false)
        assertNull(store.begin("translation"))
        // A setting change neither deletes history nor cuts off an existing recording.
        translation.put(AssistantHistoryRow("translation", 0, "translation", "Hello", "你好"))
        translation.close()
        waitFor { store.entries.value.size == 2 && store.entries.value.all { it.endedAt != null } }
        assertTrue(store.entries.value.any { it.id == call.id })

        store.setEnabled("call", true)
        store.begin("call")!!.close()
        assertNull(store.begin("translation"))
        assertTrue(preferences(account).getBoolean("enabled_call", false))
        assertFalse(preferences(account).getBoolean("enabled_translation", true))
        val otherAccount = "history-independent-other-${UUID.randomUUID()}"
        val other = AssistantHistoryStore.get(context, otherAccount) { otherAccount }
        assertTrue(other.enabled("call").value)
        assertTrue(other.enabled("translation").value)
        store.clear()
        waitFor { store.entries.value.isEmpty() }
    }

    @Test fun retentionKeepsTwoHundredRealConversationsAndEmptyAttemptsDoNotEvictThem() {
        val account = "history-retention-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        var oldest = ""
        var last: AssistantHistoryStore.Recording? = null
        repeat(200) { index ->
            val recording = store.begin("call")!!
            if (index == 0) oldest = recording.id
            recording.put(AssistantHistoryRow("u", 0, "user", "message $index"))
            if (index < 199) recording.close() else last = recording
            waitFor { store.entries.value.firstOrNull()?.id == recording.id }
        }
        store.begin("call")!!.close()
        last!!.put(AssistantHistoryRow("u", 0, "user", "after empty attempt"))
        waitFor { store.entries.value.firstOrNull()?.rows?.firstOrNull()?.text == "after empty attempt" }
        assertEquals(200, store.entries.value.size)
        assertTrue(store.entries.value.any { it.id == oldest })
        last!!.close()
        val newest = store.begin("translation")!!
        newest.put(AssistantHistoryRow("t", 0, "translation", "newest")); newest.close()
        waitFor { store.entries.value.firstOrNull()?.id == newest.id && store.entries.value.first().endedAt != null }
        assertEquals(200, store.entries.value.size)
        assertFalse(store.entries.value.any { it.id == oldest })
        assertFalse(store.error.value)
        store.clear()
    }

    @Test fun textIsDurableAccountScopedAndDeletingAnActiveSessionPreventsResurrection() {
        val account = "history-test-${UUID.randomUUID()}"
        var signedIn: String? = account
        val store = AssistantHistoryStore.get(context, account) { signedIn }
        val otherId = "other-${UUID.randomUUID()}"
        val other = AssistantHistoryStore.get(context, otherId) { otherId }
        val recording = store.begin("call")!!
        recording.put(AssistantHistoryRow("user", 0, "user", "Hello"))
        recording.put(AssistantHistoryRow("reply", 1, "assistant", "你好"))
        waitFor { store.entries.value.singleOrNull()?.rows?.size == 2 }
        assertTrue(other.entries.value.isEmpty())
        // Verify actual storage independently of the in-memory StateFlow.
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "assistant-$hash.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT text FROM rows WHERE id='user'", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("Hello", c.getString(0)) }
        }
        store.delete(recording.id)
        recording.put(AssistantHistoryRow("late", 2, "assistant", "late response"))
        recording.close()
        // A following write acts as a queue barrier before inspecting deletion.
        val next = store.begin("translation")!!
        next.put(AssistantHistoryRow("next", 0, "translation", "Goodbye", "再见", "zh", "en"))
        next.close()
        waitFor { store.entries.value.singleOrNull()?.id == next.id && store.entries.value.single().endedAt != null }
        signedIn = null
        assertNull(store.begin("call"))
        signedIn = account
        store.setEnabled("translation", false)
        assertNull(store.begin("translation"))
        store.clear()
        waitFor { store.entries.value.isEmpty() }
    }

    @Test fun sourceCorrectionsReplaceRowsAndClearWinsOverLateCallbacks() {
        val account = "history-test-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val recording = store.begin("translation")!!
        recording.put(AssistantHistoryRow("one", 0, "translation", "Hello", "你", "zh", "en"))
        recording.put(AssistantHistoryRow("one", 0, "translation", "Hello", "你好", "zh", "en"))
        waitFor { store.entries.value.firstOrNull()?.rows?.singleOrNull()?.source == "你好" }
        store.clear()
        recording.put(AssistantHistoryRow("late", 1, "translation", "later"))
        recording.close()
        val barrier = store.begin("call")!!
        barrier.put(AssistantHistoryRow("end", 0, "user", "end")); barrier.close()
        waitFor { store.entries.value.singleOrNull()?.id == barrier.id && store.entries.value.single().endedAt != null }
        assertFalse(store.error.value)
        store.clear()
    }

    @Test fun longConversationsShareAsCompleteUtf8AttachmentsWithoutBroadProviderAccess() {
        val short = historyShareIntent(context, "你好\nHello")
        assertEquals("你好\nHello", short.getStringExtra(Intent.EXTRA_TEXT))
        val text = "你好 Hello\n".repeat(20000)
        val intent = historyShareIntent(context, text)
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { assertEquals(text, it.readText()) }
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.assistant.history.files", File(context.noBackupFilesDir, "private.sqlite"))
        }
        context.contentResolver.delete(uri, null, null)
    }
}
