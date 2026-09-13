package com.we.meet.data.capture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.we.meet.data.api.dto.CaptureAudioReceiptDto
import com.we.meet.data.api.dto.CaptureCommandDto
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.api.dto.CreateCaptureDto
import com.we.meet.data.api.dto.SealCaptureDto
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PendingCaptureCommand(val key: String, val body: CaptureCommandDto)

data class LocalCapture(
    val id: String,
    val createdAt: Long,
    val createKey: String,
    val create: CreateCaptureDto,
    val remote: CaptureDto? = null,
    val command: PendingCaptureCommand? = null,
    val sealIntent: SealCaptureDto? = null,
    val nextSequence: Int = 1,
    val durationMs: Long = 0,
    val pendingBytes: Int = 0,
    val closed: Boolean = true,
    val sealed: Boolean = false,
    val interrupted: Boolean = false,
) {
    override fun toString(): String = "LocalCapture(<private>)"
}

data class LocalCaptureChunk(
    val captureId: String,
    val sequence: Int,
    val startMs: Long,
    val durationMs: Long,
    val checksum: String,
    val byteSize: Int,
    val receipt: CaptureAudioReceiptDto?,
)

/** Synchronous IO for a worker dispatcher. One account, transactional counters, bounded encrypted audio. */
class CaptureJournal private constructor(
    private val db: SQLiteDatabase,
    private val cipher: CaptureCipher,
    private val viewer: String,
    private val currentViewer: () -> String?,
    private val byteLimit: Int,
) : Closeable {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sessionAdapter = moshi.adapter(LocalCapture::class.java)
    private val receiptAdapter = moshi.adapter(CaptureAudioReceiptDto::class.java)

    @Synchronized
    private fun <T> transaction(run: () -> T): T {
        check(currentViewer() == viewer) { "Capture account changed" }
        db.beginTransaction()
        try {
            val result = run()
            check(currentViewer() == viewer) { "Capture account changed" }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun load(id: String): LocalCapture = db.rawQuery("SELECT payload FROM sessions WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()) { "Local capture missing" }
        requireNotNull(sessionAdapter.fromJson(String(cipher.decrypt("session/$id", it.getBlob(0)), Charsets.UTF_8))).also { row -> require(row.id == id) }
    }

    private fun save(value: LocalCapture) {
        val json = sessionAdapter.toJson(value).toByteArray(Charsets.UTF_8)
        require(json.size <= 256 * 1024)
        val values = ContentValues().apply { put("payload", cipher.encrypt("session/${value.id}", json)) }
        check(db.update("sessions", values, "id=?", arrayOf(value.id)) == 1)
    }

    fun list(): List<LocalCapture> = transaction {
        db.rawQuery("SELECT id FROM sessions ORDER BY created_at DESC, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(load(cursor.getString(0))) }
        }
    }

    fun get(id: String): LocalCapture = transaction { load(id) }

    fun create(title: String): LocalCapture = transaction {
        require(title.length <= 500)
        val previous = db.rawQuery("SELECT id FROM sessions ORDER BY created_at DESC, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(load(cursor.getString(0))) }
        }
        check(previous.none { !it.sealed }) { "Recover the unfinished recording first" }
        // Cloud notes provide full history; keep bounded local completed metadata.
        previous.drop(99).forEach { db.delete("sessions", "id=?", arrayOf(it.id)) }
        val row = LocalCapture(UUID.randomUUID().toString(), System.currentTimeMillis(), UUID.randomUUID().toString(),
            CreateCaptureDto(UUID.randomUUID().toString(), UUID.randomUUID().toString(), title))
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", row.id); put("created_at", row.createdAt)
            put("payload", cipher.encrypt("session/${row.id}", sessionAdapter.toJson(row).toByteArray(Charsets.UTF_8)))
        })
        row
    }

    fun update(id: String, change: (LocalCapture) -> LocalCapture): LocalCapture = transaction {
        val before = load(id)
        val after = change(before)
        require(after.id == before.id && after.createdAt == before.createdAt && after.createKey == before.createKey && after.create == before.create)
        require(after.nextSequence == before.nextSequence && after.durationMs == before.durationMs && after.pendingBytes == before.pendingBytes)
        require(!before.sealed || after.sealed)
        require(!after.sealed || (after.closed && after.pendingBytes == 0 && after.remote?.status == "stopped"))
        if (after.remote != null) require(after.remote.deviceId == after.create.deviceId)
        if (after.command != null) {
            require(after.command.body.deviceId == after.create.deviceId && after.command.body.expectedRevision > 0)
            require(UUID.fromString(after.command.key).toString() == after.command.key)
        }
        if (after.sealIntent != null) {
            require(after.closed && after.sealIntent.deviceId == after.create.deviceId && after.sealIntent.finalSequence == after.nextSequence - 1)
        }
        if (before.remote != null) {
            require(after.remote?.id == before.remote.id && after.remote.recordId == before.remote.recordId && after.remote.revision >= before.remote.revision)
        }
        if (before.command != null) require(after.command == null || after.command == before.command)
        if (before.sealIntent != null) require(after.sealIntent == before.sealIntent)
        save(after)
        if (after.sealed && !before.sealed) db.delete("chunks", "capture_id=?", arrayOf(id))
        after
    }

    fun append(id: String, samples: ShortArray): LocalCaptureChunk = transaction {
        val row = load(id)
        val audio = CaptureWave.encode(samples)
        val info = CaptureWave.inspect(audio)
        check(!row.closed && !row.sealed && row.nextSequence <= CaptureWave.MAX_CHUNKS)
        check(row.pendingBytes <= byteLimit - audio.size && row.durationMs <= CaptureWave.MAX_DURATION_MS - info.durationMs)
        val chunk = LocalCaptureChunk(id, row.nextSequence, row.durationMs, info.durationMs, info.checksum, info.byteSize, null)
        db.insertOrThrow("chunks", null, ContentValues().apply {
            put("capture_id", id); put("sequence", chunk.sequence); put("start_ms", chunk.startMs)
            put("duration_ms", chunk.durationMs); put("checksum", chunk.checksum); put("byte_size", chunk.byteSize)
            put("audio", cipher.encrypt(chunkLabel(chunk), audio))
        })
        save(row.copy(nextSequence = row.nextSequence + 1, durationMs = row.durationMs + info.durationMs, pendingBytes = row.pendingBytes + audio.size))
        chunk
    }

    /** Metadata only: do not decrypt all pending audio into RAM during recovery. */
    fun chunks(id: String): List<LocalCaptureChunk> = transaction {
        load(id)
        db.rawQuery("SELECT * FROM chunks WHERE capture_id=? ORDER BY sequence", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(chunk(cursor)) }
        }
    }

    fun audio(id: String, sequence: Int): ByteArray = transaction {
        load(id)
        db.rawQuery("SELECT * FROM chunks WHERE capture_id=? AND sequence=?", arrayOf(id, sequence.toString())).use { cursor ->
            check(cursor.moveToFirst())
            val value = chunk(cursor)
            val index = cursor.getColumnIndexOrThrow("audio")
            check(!cursor.isNull(index)) { "Audio has already been acknowledged" }
            cipher.decrypt(chunkLabel(value), cursor.getBlob(index)).also {
                val info = CaptureWave.inspect(it)
                require(info.checksum == value.checksum && info.durationMs == value.durationMs && info.byteSize == value.byteSize)
            }
        }
    }

    fun acknowledge(id: String, receipt: CaptureAudioReceiptDto): Unit = transaction {
        val row = load(id)
        db.rawQuery("SELECT * FROM chunks WHERE capture_id=? AND sequence=?", arrayOf(id, receipt.sequence.toString())).use { cursor ->
            check(cursor.moveToFirst())
            val value = chunk(cursor)
            require(receipt.stored && receipt.startMs == value.startMs && receipt.durationMs == value.durationMs &&
                receipt.checksum == value.checksum && receipt.byteSize == value.byteSize)
            if (value.receipt != null) { require(value.receipt == receipt); return@transaction }
            check(row.pendingBytes >= value.byteSize)
            // Receipt, local-audio removal and byte accounting commit together.
            db.update("chunks", ContentValues().apply {
                putNull("audio")
                put("receipt", cipher.encrypt("receipt/$id/${receipt.sequence}", receiptAdapter.toJson(receipt).toByteArray(Charsets.UTF_8)))
            }, "capture_id=? AND sequence=?", arrayOf(id, receipt.sequence.toString()))
            save(row.copy(pendingBytes = row.pendingBytes - value.byteSize))
        }
    }

    private fun chunk(cursor: Cursor): LocalCaptureChunk {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        val id = text("capture_id")
        val sequence = number("sequence").toInt()
        val index = cursor.getColumnIndexOrThrow("receipt")
        val receipt = if (cursor.isNull(index)) null else receiptAdapter.fromJson(String(cipher.decrypt("receipt/$id/$sequence", cursor.getBlob(index)), Charsets.UTF_8))
        return LocalCaptureChunk(id, sequence, number("start_ms"), number("duration_ms"), text("checksum"), number("byte_size").toInt(), receipt)
    }

    private fun chunkLabel(chunk: LocalCaptureChunk) =
        "audio/${chunk.captureId}/${chunk.sequence}/${chunk.startMs}/${chunk.durationMs}/${chunk.checksum}/${chunk.byteSize}"

    @Synchronized override fun close() { if (db.isOpen) db.close() }

    companion object {
        @Synchronized
        fun open(context: Context, viewer: String, currentViewer: () -> String?, byteLimit: Int = CaptureWave.MAX_PENDING_BYTES): CaptureJournal {
            require(viewer.isNotBlank() && viewer.length <= 256 && currentViewer() == viewer)
            require(byteLimit in 76..CaptureWave.MAX_PENDING_BYTES)
            val scope = MessageDigest.getInstance("SHA-256").digest(viewer.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            val directory = File(context.noBackupFilesDir, "meeting-captures-v1").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "$scope.db")
            val cipher = CaptureCipher.open(scope, file.exists() && file.length() > 0)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.setForeignKeyConstraintsEnabled(true)
                db.beginTransaction()
                try {
                    check(db.version in 0..1) { "Unsupported capture journal version" }
                    if (db.version == 0) {
                        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, payload BLOB NOT NULL)")
                        db.execSQL("CREATE TABLE chunks (capture_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, start_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL, checksum TEXT NOT NULL, byte_size INTEGER NOT NULL, audio BLOB, receipt BLOB, PRIMARY KEY(capture_id, sequence))")
                        db.version = 1
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                return CaptureJournal(db, cipher, viewer, currentViewer, byteLimit)
            } catch (error: Exception) { db.close(); throw error }
        }
    }
}
