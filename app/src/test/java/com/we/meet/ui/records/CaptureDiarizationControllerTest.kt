package com.we.meet.ui.records

import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.MeetingIntent
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CaptureDiarizationControllerTest {
    private class Ops : DiarizationOperations {
        var allowed = true
        var data = CaptureDiarizationStateDto(true,true,1,"asr",null,emptyList())
        var intent: MeetingIntent? = null
        var readError: Exception? = null
        var storeError: Exception? = null
        var submitError: Exception? = null
        var cancelError: Exception? = null
        var clearOnError = false
        var wait: CompletableDeferred<Unit>? = null
        var submits = 0; var cancels = 0; var publications = 0
        override fun allowed() = allowed
        override suspend fun pending(): MeetingIntent? { storeError?.let { throw it }; return intent }
        override suspend fun read() = readError?.let { Result.failure(it) } ?: Result.success(data)
        override suspend fun submit(revision: Int) {
            submits++; intent = intent ?: MeetingIntent("key","revision:$revision")
            wait?.await()
            submitError?.let { if (clearOnError) intent = null; throw it }
            intent = null
            data = data.copy(canStart=false,results=listOf(CaptureDiarizationJobDto("job",1,"queued","asr",revision,0)))
        }
        override suspend fun cancel(job: CaptureDiarizationJobDto, revision: Int) { cancels++; cancelError?.let { throw it }; data = data.copy(results=listOf(job.copy(status="canceled"))) }
        override fun published() { publications++ }
    }
    @Test fun readsNeverAutomaticallyReplayAnUncertainPaidCommand() = runBlocking {
        val ops = Ops().apply { intent = MeetingIntent("old","revision:1") }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.refresh()
        assertEquals(0,ops.submits); assertNotNull(controller.state.value.pending)
        assertTrue(controller.state.value.canSubmit)
    }
    @Test fun onlyExplicitAcceptanceStartsProcessingAndFastDuplicateClicksDoNothing() = runBlocking {
        val ops = Ops().apply { wait = CompletableDeferred() }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.submit(); assertEquals(0,ops.submits)
        controller.accept(true)
        val first = launch { controller.submit() }
        yield(); assertEquals(1,ops.submits)
        controller.submit(); assertEquals(1,ops.submits)
        ops.wait!!.complete(Unit); first.join()
        assertNull(controller.state.value.pending)
        assertFalse(controller.state.value.accepted)
    }
    @Test fun unknownOutcomeRetainsIntentUntilAnExplicitReplay() = runBlocking {
        val ops = Ops().apply { submitError = IllegalStateException("unknown") }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true); controller.submit()
        assertEquals(DiarizationFailure.UNKNOWN,controller.state.value.failure)
        assertNotNull(controller.state.value.pending)
        controller.refresh(); assertEquals(1,ops.submits)
        ops.submitError = null; controller.submit()
        assertEquals(2,ops.submits); assertNull(controller.state.value.pending)
    }
    @Test fun editingBlocksNewWorkAndRecoveryAndClearsAcceptance() = runBlocking {
        val ops = Ops()
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true); controller.editing(true)
        controller.submit(); assertEquals(0,ops.submits); assertFalse(controller.state.value.accepted)
        ops.intent = MeetingIntent("old","revision:1"); controller.refresh()
        assertFalse(controller.state.value.canSubmit)
        controller.editing(false); assertTrue(controller.state.value.canSubmit)
    }
    @Test fun accessReadFailureHidesOldPrivateHistoryAndBlocksWrites() = runBlocking {
        val ops = Ops().apply { data=data.copy(results=listOf(CaptureDiarizationJobDto("job",1,"succeeded","asr",1,2))) }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true)
        ops.readError = IllegalStateException("denied"); controller.refresh()
        assertNull(controller.state.value.data); assertFalse(controller.state.value.canSubmit)
        assertFalse(controller.state.value.shouldPoll)
    }
    @Test fun cancellationStillWorksWithPaidCreationDisabled() = runBlocking {
        val ops = Ops().apply { data=data.copy(available=false,canStart=false,results=listOf(CaptureDiarizationJobDto("job",1,"running","asr",1,0))) }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.cancel()
        assertEquals(1,ops.cancels); assertEquals("canceled",controller.state.value.data?.results?.first()?.status)
    }
    @Test fun cancellationAccessFailureDropsPrivateHistoryImmediately() = runBlocking {
        val ops = Ops().apply {
            data = data.copy(canStart=false, results=listOf(CaptureDiarizationJobDto("job",1,"running","asr",1,0)))
            cancelError = HttpException(Response.error<Any>(403,"{}".toResponseBody()))
        }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.cancel()
        assertNull(controller.state.value.data)
        assertEquals(DiarizationFailure.DENIED,controller.state.value.failure)
        assertFalse(controller.state.value.canCancel)
        assertFalse(controller.state.value.shouldPoll)
    }
    @Test fun laterStorageReadFailureIsNotMisclassifiedAsAccessFailure() = runBlocking {
        val ops = Ops()
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true)
        ops.storeError = IllegalStateException("storage unavailable")
        controller.refresh()
        assertEquals(DiarizationFailure.STORAGE,controller.state.value.failure)
        assertFalse(controller.state.value.ready)
        assertNull(controller.state.value.data)
        assertFalse(controller.state.value.canSubmit)
    }
    @Test fun versionChangesNotifyOnceAndRequireNewConfirmation() = runBlocking {
        val ops = Ops()
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true); controller.refresh()
        assertEquals(1,ops.publications)
        ops.data=ops.data.copy(recordRevision=2,activeJobId="published")
        controller.refresh(); controller.refresh()
        assertEquals(2,ops.publications); assertFalse(controller.state.value.accepted)
    }
    @Test fun storageFailureNeverEnablesPaidRequests() = runBlocking {
        val ops = Ops().apply { storeError = IllegalStateException("storage") }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true); controller.submit()
        assertEquals(DiarizationFailure.STORAGE,controller.state.value.failure)
        assertFalse(controller.state.value.canSubmit); assertEquals(0,ops.submits)
    }
    @Test fun closingDropsLateResultsAndPrivateState() = runBlocking {
        val ops = Ops().apply { wait = CompletableDeferred() }
        val controller = CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true)
        val task=launch { controller.submit() }; yield()
        controller.close(); ops.wait!!.complete(Unit); task.join()
        assertEquals(DiarizationUiState(),controller.state.value)
        assertEquals(1,ops.publications)
    }
    @Test fun definitiveConflictRequiresFreshConfirmation() = runBlocking {
        val ops = Ops().apply { submitError=HttpException(Response.error<Any>(409,"{}".toResponseBody())); clearOnError=true }
        val controller=CaptureDiarizationController(ops)
        controller.refresh(); controller.accept(true); controller.submit()
        assertEquals(DiarizationFailure.CONFLICT,controller.state.value.failure)
        assertNull(controller.state.value.pending); assertFalse(controller.state.value.canSubmit)
    }
}
