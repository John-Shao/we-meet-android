package com.we.meet.ui.records

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.we.meet.data.api.CaptureApi
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.CaptureJournal
import com.we.meet.data.capture.CaptureRecovery
import com.we.meet.data.capture.CaptureWave
import com.we.meet.data.repository.CaptureRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class CaptureRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewer = "recovery-fixture-${UUID.randomUUID()}"
    private var currentViewer: String? = viewer
    private var journal = CaptureJournal.open(context, viewer, { currentViewer })
    private val api = CaptureProtocolFixture()
    private fun controller() = CaptureRecovery(viewer, journal, CaptureRepository(api) { currentViewer })
    @After fun close() { journal.close() }
    private fun reopen(): CaptureRecovery {
        journal.close()
        journal = CaptureJournal.open(context, viewer, { currentViewer })
        return controller()
    }

    @Test fun unknownCreateUsesSameKeyAndLeaseAfterRestart() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.failCreate = true
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertNull(journal.get(local.id).remote)
        val recovered = reopen()
        recovered.load()
        assertEquals(1, api.createKeys.size)
        val started = recovered.start(local.id)
        assertFalse(started.closed)
        assertEquals(listOf(local.createKey, local.createKey), api.createKeys)
        assertEquals(local.create.leaseKey, started.create.leaseKey)
    }

    @Test fun unknownStartIsReplayedWithoutOpeningLocalRecordingOnLoad() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        api.failCommand = "start"
        assertTrue(runCatching { first.start(local.id) }.isFailure)
        val pending = journal.get(local.id).command!!
        val recovered = reopen()
        assertTrue(recovered.load().single().closed)
        assertEquals(1, api.commandKeys.size)
        val started = recovered.start(local.id)
        assertTrue(started.interrupted)
        assertEquals(pending.key, api.commandKeys[1])
        assertEquals(listOf("start", "start", "interrupt", "resume"), api.commands)
        assertFalse(started.closed)
    }

    @Test fun uploadUnknownOutcomeReconcilesReceiptWithoutResendingAudio() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        first.start(local.id)
        journal.append(local.id, ShortArray(16000))
        api.failUpload = true
        assertTrue(runCatching { first.uploadPending(local.id) }.isFailure)
        assertTrue(journal.get(local.id).pendingBytes > 0)
        val recovered = reopen()
        recovered.load()
        val synced = recovered.retryUploads(local.id)
        assertTrue(synced.closed && synced.interrupted)
        assertEquals(0, synced.pendingBytes)
        assertEquals(1, api.uploadCalls)
    }

    @Test fun unknownSealRetainsFrozenIntentAndCompletesAfterRestart() = runBlocking {
        val first = controller()
        val local = first.prepare("Fixture")
        first.start(local.id)
        journal.append(local.id, ShortArray(16000))
        first.closeLocally(local.id, false)
        api.failSeal = true
        assertTrue(runCatching { first.finish(local.id) }.isFailure)
        val intent = journal.get(local.id).sealIntent
        assertNotNull(intent)
        val recovered = reopen()
        recovered.load()
        val finished = recovered.finish(local.id)
        assertTrue(finished.sealed)
        assertEquals(listOf(intent, intent), api.seals)
        assertEquals(1, api.uploadCalls)
        assertTrue(journal.chunks(local.id).isEmpty())
    }

    @Test fun rejectedCasFailsCurrentActionAndOnlyNextActionGetsNewIntent() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.rejectCommand = true
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertEquals(1, api.commandKeys.size)
        assertNull(journal.get(local.id).command)
        assertTrue(journal.get(local.id).closed)
        controller.start(local.id)
        assertEquals(2, api.commandKeys.size)
        assertNotEquals(api.commandKeys[0], api.commandKeys[1])
    }

    @Test fun failedPauseKeepsHardwareClosedAndExactCommandForRetry() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        controller.start(local.id)
        api.failCommand = "pause"
        assertTrue(runCatching { controller.pause(local.id) }.isFailure)
        assertTrue(journal.get(local.id).closed)
        val key = journal.get(local.id).command!!.key
        controller.retryUploads(local.id)
        assertNull(journal.get(local.id).command)
        assertEquals(key, api.commandKeys.last())
        assertEquals("paused", journal.get(local.id).remote!!.status)
    }

    @Test fun mismatchedReceiptNeverDeletesAudioOrSeals() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        controller.start(local.id)
        journal.append(local.id, ShortArray(16))
        api.corruptReceipt = true
        assertTrue(runCatching { controller.finish(local.id) }.isFailure)
        assertEquals(76, journal.get(local.id).pendingBytes)
        assertNull(journal.get(local.id).sealIntent)
        assertTrue(api.seals.isEmpty())
    }

    @Test fun accountSwitchDuringRequestLeavesRecoveryPrivateAndUnconfirmed() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.afterCreate = { currentViewer = "other" }
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertTrue(runCatching { controller.load() }.isFailure)
        currentViewer = viewer
        assertNull(journal.get(local.id).remote)
        assertEquals(local.createKey, journal.get(local.id).createKey)
    }

    @Test fun localInterruptionWhileStartIsPendingCannotReopenRecording() = runBlocking {
        val controller = controller()
        val local = controller.prepare("Fixture")
        api.afterCreate = { controller.closeLocally(local.id, true); Unit }
        assertTrue(runCatching { controller.start(local.id) }.isFailure)
        assertTrue(journal.get(local.id).closed)
        assertTrue(journal.get(local.id).interrupted)
    }

}
