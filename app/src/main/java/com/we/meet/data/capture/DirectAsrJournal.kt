package com.we.meet.data.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.DirectAsrFinal
import com.we.meet.data.api.DirectAsrRange
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class DirectAsrLocal(val localId: String, val capture: String, val device: String, val lease: String,
    val key: String = UUID.randomUUID().toString(), val expected: String? = null, val job: String? = null,
    val phase: String = "preparing", val ranges: List<DirectAsrRange> = emptyList(),
    val sequence: Int = 0, val acknowledged: Int = 0, val bytes: Int = 0, val gap: Boolean = false) {
    override fun toString() = "DirectAsrLocal(<private>)"
}

/** Account-bound encrypted text outbox. Never stores provider credentials or PCM. */
class DirectAsrJournal private constructor(private val db: SQLiteDatabase, private val cipher: CaptureCipher,
    private val viewer: String, private val current: () -> String?) : Closeable {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sessions = moshi.adapter(DirectAsrLocal::class.java)
    private val finals = moshi.adapter(DirectAsrFinal::class.java)
    @Synchronized private fun <T> transaction(block: () -> T): T {
        check(current() == viewer); db.beginTransaction()
        try { return block().also { check(current() == viewer); db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
    private fun read(id: String): DirectAsrLocal? = db.rawQuery("SELECT payload FROM sessions WHERE id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) null else sessions.fromJson(String(cipher.decrypt("direct/$id", it.getBlob(0)), Charsets.UTF_8))
    }
    fun get(id: String) = transaction { read(id) }
    fun save(value: DirectAsrLocal) = transaction {
        val previous = read(value.localId)
        if(previous != null && previous.key != value.key) db.delete("finals", "id=?", arrayOf(value.localId))
        if(previous == null) db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { check(it.moveToFirst() && it.getInt(0) < 128) }
        db.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", value.localId); put("payload", cipher.encrypt("direct/${value.localId}", sessions.toJson(value).toByteArray()))
        }, SQLiteDatabase.CONFLICT_REPLACE); Unit
    }
    fun append(value: DirectAsrLocal, row: DirectAsrFinal): DirectAsrLocal = transaction {
        val old = requireNotNull(read(value.localId))
        require(row.sequence == old.sequence + 1 && row.text.isNotBlank() && row.text.length <= 10000)
        val bytes = old.bytes + row.text.toByteArray(Charsets.UTF_8).size
        require(row.sequence <= 20000 && bytes <= 4000000)
        db.insertOrThrow("finals", null, ContentValues().apply {
            put("id", value.localId); put("sequence", row.sequence)
            put("payload", cipher.encrypt("direct-final/${value.localId}/${row.sequence}", finals.toJson(row).toByteArray()))
        })
        value.copy(sequence = row.sequence, bytes = bytes).also { save(it) }
    }
    fun rows(id: String, after: Int = 0, limit: Int = 50, newest: Boolean = false): List<DirectAsrFinal> = transaction {
        require(limit in 1..50)
        db.rawQuery("SELECT sequence,payload FROM finals WHERE id=? AND sequence>? ORDER BY sequence ${if(newest) "DESC" else "ASC"} LIMIT ?", arrayOf(id, after.toString(), limit.toString())).use {
            buildList { while(it.moveToNext()) add(requireNotNull(finals.fromJson(String(cipher.decrypt("direct-final/$id/${it.getInt(0)}", it.getBlob(1)), Charsets.UTF_8)))) }
                .let { rows -> if(newest) rows.reversed() else rows }
        }
    }
    @Synchronized override fun close() { db.close() }
    companion object {
        @Synchronized fun open(context: Context, viewer: String, current: () -> String?): DirectAsrJournal {
            require(viewer.isNotBlank() && viewer.length <= 256 && current() == viewer)
            val hash = MessageDigest.getInstance("SHA-256").digest(viewer.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(context.noBackupFilesDir, "direct-asr-v1").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "$hash.db")
            val cipher = CaptureCipher.open(hash, file.exists() && file.length() > 0)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY NOT NULL,payload BLOB NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS finals(id TEXT NOT NULL,sequence INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(id,sequence))")
            return DirectAsrJournal(db, cipher, viewer, current)
        }
    }
}
