package com.we.meet.data.capture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class MeetingIntentKind { CAPTURE_ASR, SUMMARY_REQUEST, SUMMARY_AUTOMATION, HUMAN_REVIEW, SUMMARY_TASK, RECORD_QUESTION, DOCUMENT_EXPORT, DOCUMENT_EXPORT_RETRY, SUMMARY_NOTICE_RETRY, SUMMARY_SHARE, ONLINE_CAPTURE, PRIVATE_TRANSLATION, INTERPRETATION_CHANNEL, INTERPRETATION_LISTEN, CLOUD_RECORDING, CAPTURE_TRANSLATION }

data class MeetingIntent(val key: String, val body: String) {
    override fun toString() = "MeetingIntent(<private>)"
}

/** Bounded encrypted intents independent of audio-history pruning; no automatic expiry or replay. */
class MeetingIntentStore private constructor(
    private val db: SQLiteDatabase,
    private val cipher: CaptureCipher,
    private val viewer: String,
    private val currentViewer: () -> String?,
) : Closeable {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MeetingIntent::class.java)

    @Synchronized private fun <T> transaction(run: () -> T): T {
        check(currentViewer() == viewer)
        db.beginTransaction()
        try {
            val result = run()
            check(currentViewer() == viewer)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun address(kind: MeetingIntentKind, resource: String): String {
        require(UUID.fromString(resource).toString() == resource)
        return "meeting-intent/${kind.name}/$resource"
    }
    private fun load(address: String): MeetingIntent? = db.rawQuery("SELECT payload FROM intents WHERE address=?", arrayOf(address)).use {
        if (!it.moveToFirst()) null
        else requireNotNull(adapter.fromJson(String(cipher.decrypt(address, it.getBlob(0)), Charsets.UTF_8))).also { intent ->
            require(UUID.fromString(intent.key).toString() == intent.key && intent.body.toByteArray(Charsets.UTF_8).size <= 65536)
        }
    }
    fun get(kind: MeetingIntentKind, resource: String): MeetingIntent? = transaction { load(address(kind, resource)) }

    /** If an outcome is unknown, the old body wins even when the user changed UI options. */
    fun getOrCreate(kind: MeetingIntentKind, resource: String, body: String): MeetingIntent = transaction {
        val address = address(kind, resource)
        val previous = load(address)
        if (previous != null) return@transaction previous
        require(body.toByteArray(Charsets.UTF_8).size in 2..65536)
        db.rawQuery("SELECT COUNT(*) FROM intents", null).use { check(it.moveToFirst() && it.getInt(0) < 128) { "Resolve existing requests first" } }
        val intent = MeetingIntent(UUID.randomUUID().toString(), body)
        db.insertOrThrow("intents", null, ContentValues().apply {
            put("address", address)
            put("payload", cipher.encrypt(address, adapter.toJson(intent).toByteArray(Charsets.UTF_8)))
        })
        intent
    }

    /** Only the exact acknowledged/rejected request may be cleared. */
    fun resolve(kind: MeetingIntentKind, resource: String, expected: MeetingIntent): Unit = transaction {
        val address = address(kind, resource)
        val current = load(address) ?: return@transaction
        require(current == expected)
        check(db.delete("intents", "address=?", arrayOf(address)) == 1)
    }

    @Synchronized override fun close() { if (db.isOpen) db.close() }

    companion object {
        @Synchronized fun open(context: Context, viewer: String, currentViewer: () -> String?): MeetingIntentStore {
            require(viewer.isNotBlank() && viewer.length <= 256 && currentViewer() == viewer)
            val hash = MessageDigest.getInstance("SHA-256").digest(viewer.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(context.noBackupFilesDir, "meeting-intents-v1").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "$hash.db")
            val cipher = CaptureCipher.open(hash, file.exists() && file.length() > 0)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.beginTransaction()
                try {
                    check(db.version in 0..1)
                    if (db.version == 0) {
                        db.execSQL("CREATE TABLE intents (address TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
                        db.version = 1
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                return MeetingIntentStore(db, cipher, viewer, currentViewer)
            } catch (error: Exception) { db.close(); throw error }
        }
    }
}
