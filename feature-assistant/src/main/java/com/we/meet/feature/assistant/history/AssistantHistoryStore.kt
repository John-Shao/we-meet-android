package com.we.meet.feature.assistant.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantHistoryRow(
    val id: String, val order: Int, val role: String, val text: String,
    val source: String = "", val sourceLanguage: String = "", val targetLanguage: String = "",
) {
    override fun toString() = "AssistantHistoryRow(<private>)"
}

data class AssistantHistoryEntry(
    val id: String, val kind: String, val startedAt: Long, val endedAt: Long?,
    val rows: List<AssistantHistoryRow>,
) {
    override fun toString() = "AssistantHistoryEntry(<private>)"
}

/** Account-scoped, local text only. A single worker orders creation, updates and deletion.
 * Deleted sessions cannot be resurrected by a late transcript callback.
 * The database lives outside Android backup; neither microphone nor reply PCM is persisted.
 */
class AssistantHistoryStore private constructor(context: Context, account: String, private val allowed: () -> Boolean) {
    private val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val path = File(context.noBackupFilesDir, "assistant-$key.sqlite")
    private val prefs = context.getSharedPreferences("assistant-history-$key", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableEntries = MutableStateFlow<List<AssistantHistoryEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<(SQLiteDatabase) -> Unit>(256)

    init {
        scope.launch {
            // Open failures remain visible; audio sessions must never fail because history failed.
            val db = runCatching {
                SQLiteDatabase.openOrCreateDatabase(path, null).apply {
                    setForeignKeyConstraintsEnabled(true)
                    execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, kind TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER)")
                    execSQL("CREATE TABLE IF NOT EXISTS rows (session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL, source_lang TEXT NOT NULL, target_lang TEXT NOT NULL, PRIMARY KEY(session,id))")
                    // A previous process may have died before marking its session ended.
                    execSQL("UPDATE sessions SET ended = started WHERE ended IS NULL")
                }
            }.getOrElse { mutableError.value = true; operations.close(); return@launch }
            try {
                runCatching { refresh(db) }.onFailure { mutableError.value = true }
                for (operation in operations) {
                    if (!allowed()) continue
                    runCatching { operation(db); refresh(db) }
                        .onFailure { mutableError.value = true }
                }
            } finally { db.close() }
        }
    }

    fun setEnabled(value: Boolean) {
        if (!allowed()) return
        mutableEnabled.value = value
        prefs.edit().putBoolean("enabled", value).apply()
    }

    fun begin(kind: String): Recording? {
        if (!allowed() || !enabled.value) return null
        require(kind == "call" || kind == "translation")
        val id = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        enqueue { db ->
            db.execSQL("INSERT INTO sessions(id,kind,started) VALUES (?,?,?)", arrayOf(id, kind, started))
        }
        return Recording(id)
    }

    inner class Recording internal constructor(val id: String) : AutoCloseable {
        private val closed = AtomicBoolean()
        @Synchronized fun put(row: AssistantHistoryRow) {
            if (closed.get() || row.text.isBlank() && row.source.isBlank()) return
            if (row.order !in 0 until 2000 || row.text.length > 20000 || row.source.length > 20000) {
                mutableError.value = true
                return
            }
            enqueue { db ->
                db.execSQL("INSERT OR REPLACE INTO rows(session,id,position,role,text,source,source_lang,target_lang) SELECT ?,?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM sessions WHERE id=?)",
                    arrayOf(id, row.id, row.order, row.role, row.text, row.source, row.sourceLanguage, row.targetLanguage, id))
                db.execSQL("DELETE FROM sessions WHERE EXISTS (SELECT 1 FROM rows WHERE session=sessions.id) AND id NOT IN (SELECT id FROM sessions WHERE EXISTS (SELECT 1 FROM rows WHERE session=sessions.id) ORDER BY started DESC, rowid DESC LIMIT 200)")
            }
        }
        @Synchronized override fun close() {
            if (closed.compareAndSet(false, true)) enqueue { db ->
                db.execSQL("UPDATE sessions SET ended=? WHERE id=?", arrayOf(System.currentTimeMillis(), id))
                db.execSQL("DELETE FROM sessions WHERE id=? AND NOT EXISTS (SELECT 1 FROM rows WHERE session=?)", arrayOf(id, id))
            }
        }
    }

    fun delete(id: String) = enqueue { it.execSQL("DELETE FROM sessions WHERE id=?", arrayOf(id)) }
    fun clear() = enqueue { it.execSQL("DELETE FROM sessions") }
    private fun enqueue(operation: (SQLiteDatabase) -> Unit) {
        if (allowed() && !operations.trySend(operation).isSuccess) mutableError.value = true
    }

    private fun refresh(db: SQLiteDatabase) {
        val rows = linkedMapOf<String, MutableList<AssistantHistoryRow>>()
        db.rawQuery("SELECT session,id,position,role,text,source,source_lang,target_lang FROM rows ORDER BY position", null).use { c ->
            while (c.moveToNext()) rows.getOrPut(c.getString(0)) { mutableListOf() }.add(
                AssistantHistoryRow(c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
        }
        val result = mutableListOf<AssistantHistoryEntry>()
        db.rawQuery("SELECT id,kind,started,ended FROM sessions ORDER BY started DESC, rowid DESC", null).use { c ->
            while (c.moveToNext()) {
                val content = rows[c.getString(0)] ?: continue
                result += AssistantHistoryEntry(c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), content)
            }
        }
        mutableEntries.value = result
    }

    companion object {
        private val stores = mutableMapOf<String, AssistantHistoryStore>()
        @Synchronized fun get(context: Context, account: String, currentAccount: () -> String?): AssistantHistoryStore {
            val identity = context.noBackupFilesDir.absolutePath + ":" + account
            return stores.getOrPut(identity) { AssistantHistoryStore(context.applicationContext, account) { currentAccount() == account } }
        }
    }
}
