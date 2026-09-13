package com.we.meet.ui.records

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.dto.CaptureAudioReceiptDto
import com.we.meet.data.api.dto.CaptureCommandDto
import com.we.meet.data.api.dto.CaptureDto
import com.we.meet.data.api.dto.SealCaptureDto
import com.we.meet.data.capture.CaptureJournal
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.capture.LocalCaptureChunk
import com.we.meet.data.capture.PendingCaptureCommand
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureJournalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "capture-fixture-${UUID.randomUUID()}"
    private var currentViewer: String? = viewer
    private val journals = mutableListOf<CaptureJournal>()
    private fun open(limit: Int = CaptureWave.MAX_PENDING_BYTES) = CaptureJournal.open(context, viewer, { currentViewer }, limit).also { journals += it }
    private fun receipt(chunk: LocalCaptureChunk) = CaptureAudioReceiptDto(UUID.randomUUID().toString(), chunk.sequence,
        chunk.startMs, chunk.durationMs, chunk.checksum, chunk.byteSize, true)
    private fun file(): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(viewer.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(context.noBackupFilesDir, "meeting-captures-v1/$hash.db")
    }
    @After fun close() { journals.forEach { it.close() } }

    @Test fun reopenPreservesOriginalLeaseCommandAndNumbering() {
        val journal = open()
        val local = journal.create("Private title fixture")
        val command = PendingCaptureCommand(UUID.randomUUID().toString(), CaptureCommandDto("start", local.create.deviceId, 1))
        journal.update(local.id) { it.copy(command = command, closed = false) }
        val pcm = ShortArray(16000) { (it % 32000).toShort() }
        val first = journal.append(local.id, pcm)
        journal.close()
        val reopened = open()
        val restored = reopened.get(local.id)
        assertEquals(local.create.leaseKey, restored.create.leaseKey)
        assertEquals(local.createKey, restored.createKey)
        assertEquals(command, restored.command)
        assertEquals(2, restored.nextSequence)
        assertEquals(1000L, restored.durationMs)
        assertArrayEquals(CaptureWave.encode(pcm), reopened.audio(local.id, first.sequence))
        val second = reopened.append(local.id, ShortArray(16))
        assertEquals(2, second.sequence)
        assertEquals(1000L, second.startMs)
    }

    @Test fun acknowledgementAtomicallyDropsOnlyMatchedAudioAndIsIdempotent() {
        val journal = open()
        val local = journal.create("Fixture")
        journal.update(local.id) { it.copy(closed = false) }
        val chunk = journal.append(local.id, ShortArray(16))
        val receipt = receipt(chunk)
        assertTrue(runCatching { journal.acknowledge(local.id, receipt.copy(checksum = "bad")) }.isFailure)
        assertEquals(chunk.byteSize, journal.audio(local.id, chunk.sequence).size)
        journal.acknowledge(local.id, receipt)
        journal.acknowledge(local.id, receipt)
        assertEquals(0, journal.get(local.id).pendingBytes)
        assertEquals(receipt, journal.chunks(local.id).single().receipt)
        assertTrue(runCatching { journal.audio(local.id, chunk.sequence) }.isFailure)
        journal.close()
        assertEquals(0, open().get(local.id).pendingBytes)
    }

    @Test fun bufferLimitRollsBackNumberAndBytesWithoutDroppingPendingChunk() {
        val journal = open(100)
        val local = journal.create("Fixture")
        journal.update(local.id) { it.copy(closed = false) }
        journal.append(local.id, ShortArray(16))
        assertTrue(runCatching { journal.append(local.id, ShortArray(16)) }.isFailure)
        assertEquals(2, journal.get(local.id).nextSequence)
        assertEquals(76, journal.get(local.id).pendingBytes)
        assertEquals(1, journal.chunks(local.id).size)
    }

    @Test fun oneUnfinishedCaptureAndImmutablePendingCommand() {
        val journal = open()
        val local = journal.create("Fixture")
        assertTrue(runCatching { journal.create("Second") }.isFailure)
        assertTrue(runCatching { journal.update(local.id) { it.copy(createKey = UUID.randomUUID().toString()) } }.isFailure)
        val command = PendingCaptureCommand(UUID.randomUUID().toString(), CaptureCommandDto("start", local.create.deviceId, 1))
        journal.update(local.id) { it.copy(command = command) }
        assertTrue(runCatching { journal.update(local.id) { it.copy(command = command.copy(key = UUID.randomUUID().toString())) } }.isFailure)
        assertEquals(command, journal.get(local.id).command)
    }

    @Test fun accountSwitchRefusesOldStoreAndNewAccountHasSeparateJournal() {
        val journal = open()
        val local = journal.create("Private title fixture")
        currentViewer = "another-${UUID.randomUUID()}"
        assertTrue(runCatching { journal.get(local.id) }.isFailure)
        assertTrue(runCatching { journal.update(local.id) { it.copy(closed = false) } }.isFailure)
        CaptureJournal.open(context, currentViewer!!, { currentViewer }).use { assertTrue(it.list().isEmpty()) }
        currentViewer = viewer
        assertEquals(local.id, journal.list().single().id)
    }

    @Test fun accountChangeDuringTransactionRollsBackMutation() {
        val journal = open()
        val local = journal.create("Fixture")
        assertTrue(runCatching {
            journal.update(local.id) { currentViewer = "other"; it.copy(closed = false) }
        }.isFailure)
        currentViewer = viewer
        assertTrue(journal.get(local.id).closed)
    }

    @Test fun metadataIsEncryptedAndTamperingDoesNotSilentlyEraseRecovery() {
        val journal = open()
        val local = journal.create("Private title fixture")
        journal.close()
        val raw = String(file().readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(local.create.leaseKey))
        assertFalse(raw.contains("Private title fixture"))
        SQLiteDatabase.openDatabase(file().absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val blob = db.rawQuery("SELECT payload FROM sessions WHERE id=?", arrayOf(local.id)).use { cursor -> cursor.moveToFirst(); cursor.getBlob(0) }
            blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
            db.update("sessions", ContentValues().apply { put("payload", blob) }, "id=?", arrayOf(local.id))
        }
        val reopened = open()
        assertTrue(runCatching { reopened.list() }.isFailure)
        assertTrue(runCatching { reopened.create("Must not overwrite") }.isFailure)
    }

    @Test fun sealingRequiresAllPendingAudioResolvedAndLocksTheFinalSequence() {
        val journal = open()
        val local = journal.create("Fixture")
        journal.update(local.id) { it.copy(closed = false) }
        val chunk = journal.append(local.id, ShortArray(16))
        val remote = CaptureDto(UUID.randomUUID().toString(), UUID.randomUUID().toString(), local.create.deviceId,
            "stopped", 3, "2026-09-13T00:00:00Z", mediaStatus = "saved", lastAckedSequence = 1)
        assertTrue(runCatching { journal.update(local.id) { it.copy(closed = true, sealed = true, remote = remote) } }.isFailure)
        journal.acknowledge(local.id, receipt(chunk))
        assertTrue(runCatching { journal.update(local.id) { it.copy(closed = true, sealIntent = SealCaptureDto(local.create.deviceId, 0, false)) } }.isFailure)
        journal.update(local.id) { it.copy(closed = true, sealed = true, remote = remote, sealIntent = SealCaptureDto(local.create.deviceId, 1, false)) }
        assertTrue(journal.chunks(local.id).isEmpty())
        assertTrue(runCatching { journal.update(local.id) { it.copy(closed = false) } }.isFailure)
        assertTrue(runCatching { journal.append(local.id, ShortArray(16)) }.isFailure)
        assertNotEquals(local.id, journal.create("Next recording").id)
    }
}
