package com.we.meet.ui.ai

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.feature.assistant.history.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

internal fun historyTestJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); bitmap.recycle() }.toByteArray()
}

class CallPhotoHistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(10) } }
    private fun key(account: String) = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun legacyTextSurvivesAndSavedPhotoIsReloadedAndDeletedWithItsConversation() {
        val account = "photo-history-${UUID.randomUUID()}"
        val path = File(context.noBackupFilesDir, "assistant-${key(account)}.sqlite")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, kind TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER)")
            db.execSQL("CREATE TABLE rows (session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, PRIMARY KEY(session,id))")
            db.execSQL("INSERT INTO sessions VALUES ('legacy','translation',1,2)")
            db.execSQL("INSERT INTO rows VALUES ('legacy','old',0,'translation','Old text','','','')")
        }
        val store = AssistantHistoryStore.get(context, account) { account }
        waitFor { store.entries.value.singleOrNull()?.id == "legacy" }
        assertEquals("Old text", store.entries.value.single().rows.single().text)
        val jpeg = historyTestJpeg()
        val call = store.begin("call")!!
        call.put(AssistantHistoryRow("photo", 1, "user", "", photo = AssistantHistoryPhoto.Memory(jpeg)))
        call.put(AssistantHistoryRow("q", 0, "user", "What is this?")); call.close()
        waitFor { store.entries.value.firstOrNull { it.id == call.id }?.endedAt != null }
        val rows = store.entries.value.first { it.id == call.id }.rows
        assertEquals(listOf("q", "photo"), rows.map { it.id })
        val file = (rows.last().photo as AssistantHistoryPhoto.Stored).file
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertArrayEquals(jpeg, file.readBytes())
        store.setEnabled("call", false); assertNull(store.begin("call"))
        store.clear("call")
        waitFor { store.entries.value.singleOrNull()?.id == "legacy" }
        assertFalse(file.exists()); assertFalse(store.error.value)
        store.clear()
    }

    @Test fun latePhotoCannotResurrectDeletedHistoryAndRetentionRemovesOldPhotoFiles() {
        val account = "photo-retention-${UUID.randomUUID()}"
        val store = AssistantHistoryStore.get(context, account) { account }
        val jpeg = historyTestJpeg()
        val deleted = store.begin("call")!!
        deleted.put(AssistantHistoryRow("photo", 0, "user", "", photo = AssistantHistoryPhoto.Memory(jpeg)))
        waitFor { store.entries.value.singleOrNull()?.id == deleted.id }
        val deletedFile = (store.entries.value.single().rows.single().photo as AssistantHistoryPhoto.Stored).file
        store.delete(deleted.id)
        deleted.put(AssistantHistoryRow("late", 1, "user", "", photo = AssistantHistoryPhoto.Memory(jpeg))); deleted.close()
        val oldest = store.begin("call")!!
        oldest.put(AssistantHistoryRow("photo", 0, "user", "", photo = AssistantHistoryPhoto.Memory(jpeg))); oldest.close()
        waitFor { store.entries.value.singleOrNull()?.id == oldest.id && store.entries.value.single().endedAt != null }
        assertFalse(deletedFile.exists())
        val oldestFile = (store.entries.value.single().rows.single().photo as AssistantHistoryPhoto.Stored).file
        var newest = ""
        repeat(200) { index ->
            val call = store.begin("call")!!; newest = call.id
            call.put(AssistantHistoryRow("text", 0, "user", "Conversation $index")); call.close()
            // Avoid overflowing the deliberately bounded worker queue.
            waitFor { store.entries.value.firstOrNull { it.id == call.id }?.endedAt != null }
        }
        waitFor { store.entries.value.size == 200 && store.entries.value.firstOrNull()?.id == newest }
        assertFalse(oldestFile.exists()); assertFalse(store.error.value)
        assertTrue(oldestFile.parentFile!!.listFiles().orEmpty().isEmpty())
        store.clear()
    }
}
