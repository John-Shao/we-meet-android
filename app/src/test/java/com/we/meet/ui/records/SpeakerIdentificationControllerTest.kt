package com.we.meet.ui.records

import com.we.meet.data.IdentificationFixtures as F
import com.we.meet.data.api.dto.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SpeakerIdentificationControllerTest {
    private inner class Operations : IdentificationOperations {
        var available = true
        var intent: IdentitySubmissionDto? = null
        var current = IdentityResponseDto(1, null)
        var reads: suspend (String?) -> Result<IdentityResponseDto> = { Result.success(current) }
        var directory: suspend (String?, Int, String, Int) -> Result<IdentityCandidatesDto> = { bank, rev, _, _ ->
            Result.success(IdentityCandidatesDto(rev, bank, listOf(IdentityPersonDto(if (bank == null) F.OWNER else F.ORG, "Synthetic")), null))
        }
        var write: suspend (IdentitySubmissionDto) -> Result<IdentityResponseDto> = {
            current = F.response(true, it.requestKey)
            Result.success(current)
        }
        var savedRevision = 0
        var refreshedRevision = 0
        val submissions = mutableListOf<IdentitySubmissionDto>()
        val readKeys = mutableListOf<String?>()
        val decisions = mutableListOf<Pair<String, Int>>()
        var stops = 0
        var changes = 0
        var cancellations = 0
        override fun allowed() = available
        override fun pending() = intent
        override fun remember(body: IdentitySubmissionDto) { require(intent == null || intent == body); intent = body }
        override fun acknowledge(key: String) { if (intent?.requestKey == key) intent = null }
        override suspend fun read(key: String?): Result<IdentityResponseDto> { readKeys += key; return reads(key) }
        override suspend fun options(revision: Int, offset: Int): Result<IdentityOptionsDto> { refreshedRevision = revision; return Result.success(F.options(revision)) }
        override suspend fun people(organization: String?, revision: Int, query: String, offset: Int) = directory(organization, revision, query, offset)
        override suspend fun submit(body: IdentitySubmissionDto): Result<IdentityResponseDto> { submissions += body; return write(body) }
        override suspend fun cancel(key: String, revision: Int): Result<IdentityResponseDto> {
            cancellations++; current = F.response(key = key).let { it.copy(request = it.request!!.copy(jobs = it.request.jobs.map { job -> job.copy(status = "canceled", suggestion = null) })) }
            return Result.success(current)
        }
        override suspend fun decide(speaker: String, suggestion: IdentitySuggestionDto, confirm: Boolean, revision: Int): Result<RecordSpeakerDto> {
            savedRevision = revision; decisions += speaker to revision
            current = current.copy(recordRevision = revision + 1, request = current.request!!.copy(jobs = current.request!!.jobs.map { job ->
                if (job.speakerId != speaker) job else job.copy(suggestion = suggestion.copy(state = if (confirm) "confirmed" else "rejected", candidate = null, canConfirm = false, queryIntervals = emptyList()))
            }))
            return Result.success(RecordSpeakerDto(speaker, "Speaker", "diarized", displayName = "Synthetic", recordRevision = revision + 1))
        }
        override fun changed() { changes++ }
        override fun stopPreview() { stops++ }
    }
    @Test fun openingOnlyReadsAndRequiresAnExplicitPersonSelection() = runBlocking {
        val ops = Operations(); val controller = SpeakerIdentificationController(ops, 1)
        controller.refresh()
        assertEquals("personal", controller.state.value.scope)
        assertEquals(setOf(F.SPEAKER), controller.state.value.targets)
        assertFalse(controller.state.value.canSubmit)
        controller.submit(); assertTrue(ops.submissions.isEmpty())
        controller.person(controller.state.value.people!!.results.single(), true)
        assertTrue(controller.state.value.canSubmit)
        controller.submit()
        assertEquals(listOf(F.OWNER), ops.submissions.single().userIds)
        assertEquals(listOf(F.SPEAKER), ops.submissions.single().speakerIds)
        assertNull(ops.submissions.single().organizationId)
        controller.submit(); assertEquals(1, ops.submissions.size)
    }
    @Test fun closingDropsPrivateNamesAndLateResultsWithoutLosingTheOriginalCommand() = runBlocking {
        val ops = Operations(); val controller = SpeakerIdentificationController(ops, 1)
        controller.refresh(); controller.person(controller.state.value.people!!.results.single(), true)
        val pending = CompletableDeferred<Result<IdentityResponseDto>>()
        ops.write = { pending.await() }
        val saving = async(start = CoroutineStart.UNDISPATCHED) { controller.submit() }
        val command = requireNotNull(ops.intent)
        controller.close(); pending.complete(Result.success(F.response(true, command.requestKey))); saving.await()
        assertTrue(controller.state.value.selected.isEmpty()); assertNull(controller.state.value.response)
        assertEquals(command, ops.intent)
        assertEquals(command, SpeakerIdentificationController(ops, 1).state.value.retry)
        assertTrue(ops.stops > 0)
    }
    @Test fun unknownTransportResultRetriesTheExactCommandOnlyOnExplicitAction() = runBlocking {
        val ops = Operations(); ops.write = { Result.failure(IOException("uncertain")) }
        val controller = SpeakerIdentificationController(ops, 1)
        controller.refresh(); controller.person(controller.state.value.people!!.results.single(), true); controller.submit()
        assertNull(controller.state.value.response); assertTrue(controller.state.value.selected.isEmpty())
        assertEquals(IdentificationFailure.REQUEST, controller.state.value.failure)
        assertNotNull(controller.state.value.retry)
        controller.poll(); assertEquals(1, ops.submissions.size)
        controller.submit(retry = true); assertEquals(ops.submissions[0], ops.submissions[1])
    }
    @Test fun aSecondPanelRecoversTheOriginalCommandWithoutSendingAnotherRequest() = runBlocking {
        val ops = Operations(); ops.write = { Result.failure(IOException("uncertain")) }
        val first = SpeakerIdentificationController(ops, 1)
        val second = SpeakerIdentificationController(ops, 1)
        first.refresh(); second.refresh()
        first.person(first.state.value.people!!.results.single(), true)
        second.person(second.state.value.people!!.results.single(), true)
        first.submit(); val original = requireNotNull(ops.intent)
        second.submit()
        assertEquals(1, ops.submissions.size)
        assertEquals(original, second.state.value.retry)
        ops.reads = { Result.failure(IOException("unavailable")) }
        second.refresh(); assertEquals(original.requestKey, ops.readKeys.last())
        second.submit(retry = true)
        assertEquals(listOf(original, original), ops.submissions)
    }
    @Test fun closingAndReopeningCanRecoverTheAcceptedOriginalBatch() = runBlocking {
        val ops = Operations(); ops.intent = F.submission(); ops.current = F.response(true)
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        assertNull(ops.intent); assertNull(controller.state.value.retry)
        assertEquals(F.KEY, controller.state.value.response!!.request!!.requestKey)
        assertTrue(ops.submissions.isEmpty())
    }
    @Test fun aConflictHidesNamesAndRefreshUsesTheCurrentServerVersion() = runBlocking {
        val ops = Operations(); ops.current = F.response()
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        ops.reads = { Result.failure(HttpException(Response.error<Any>(409, "{}".toResponseBody()))) }
        controller.poll(); assertEquals(IdentificationFailure.CONFLICT, controller.state.value.failure)
        assertNull(controller.state.value.response); assertNull(controller.state.value.people)
        ops.current = F.response(revision = 4); ops.reads = { Result.success(ops.current) }
        controller.refresh(); assertEquals(4, ops.refreshedRevision)
        assertNull(controller.state.value.failure)
        controller.decide(controller.state.value.response!!.request!!.jobs.single(), true)
        assertEquals(4, ops.savedRevision)
    }
    @Test fun changingLibrariesClearsSelectionsAndRejectsALatePreviousDirectory() = runBlocking {
        val ops = Operations(); val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        val old = CompletableDeferred<Result<IdentityCandidatesDto>>()
        ops.directory = { bank, rev, _, _ -> if (bank == null) old.await() else Result.success(IdentityCandidatesDto(rev, bank, listOf(IdentityPersonDto(F.ORG, "New library")), null)) }
        val previous = async(start = CoroutineStart.UNDISPATCHED) { controller.search() }
        controller.scope(F.ORG)
        old.complete(Result.success(IdentityCandidatesDto(1, null, listOf(IdentityPersonDto(F.OWNER, "Old private name")), null)))
        previous.await()
        assertEquals("New library", controller.state.value.people!!.results.single().name)
        assertTrue(controller.state.value.selected.isEmpty())
    }
    @Test fun decisionsWaitForTheWholeBatchAndUseEachNewRevision() = runBlocking {
        val ops = Operations()
        val first = F.response().request!!.jobs.single()
        val second = first.copy(id = F.KEY, speakerId = F.ORG, suggestion = first.suggestion!!.copy(id = F.RECORD))
        ops.current = F.response().let { it.copy(request = it.request!!.copy(processing = true, jobs = listOf(first.copy(suggestion = first.suggestion!!.copy(canConfirm = false)), second))) }
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        controller.decide(first, true); assertTrue(ops.decisions.isEmpty())
        ops.current = F.response().let { it.copy(request = it.request!!.copy(jobs = listOf(first, second))) }
        controller.poll()
        controller.decide(first, true)
        controller.decide(controller.state.value.response!!.request!!.jobs[1], true)
        assertEquals(listOf(F.SPEAKER to 1, F.ORG to 2), ops.decisions)
        assertEquals(2, ops.changes)
        assertFalse(controller.state.value.shouldPoll)
    }
    @Test fun completedPendingSuggestionsAreRevalidatedAndInvalidationDropsNames() = runBlocking {
        val ops = Operations(); ops.current = F.response()
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh(); assertTrue(controller.state.value.shouldPoll)
        ops.current = F.response().let { it.copy(request = it.request!!.copy(jobs = listOf(it.request.jobs.single().copy(suggestion = F.suggestion().copy(
            state = "invalidated", candidate = null, canConfirm = false, queryIntervals = emptyList()))))) }
        controller.poll(); assertNull(controller.state.value.response!!.request!!.jobs.single().suggestion!!.candidate)
        assertFalse(controller.state.value.shouldPoll)
    }
    @Test fun temporaryReadFailureHidesNamesButStillAllowsCancellation() = runBlocking {
        val ops = Operations(); ops.current = F.response()
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        ops.reads = { Result.failure(HttpException(Response.error<Any>(503, "{}".toResponseBody()))) }
        controller.poll(); assertNull(controller.state.value.response); assertTrue(controller.canCancel)
        controller.cancel(); assertEquals(1, ops.cancellations)
    }
    @Test fun accessFailureDoesNotAllowRetriesDecisionsOrCancellation() = runBlocking {
        val ops = Operations(); ops.current = F.response()
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        ops.reads = { Result.failure(HttpException(Response.error<Any>(403, "{}".toResponseBody()))) }
        controller.poll(); assertFalse(controller.canCancel)
        controller.decide(F.response().request!!.jobs.single(), true); controller.submit(); controller.cancel()
        assertEquals(0, ops.cancellations); assertTrue(ops.decisions.isEmpty()); assertTrue(ops.submissions.isEmpty())
    }
    @Test fun anInvalidOrDuplicateSelectionCannotExpandTheFiftyPersonPool() = runBlocking {
        val ops = Operations()
        ops.directory = { bank, rev, _, offset -> Result.success(IdentityCandidatesDto(rev, bank,
            (offset until minOf(offset + 25, 51)).map { IdentityPersonDto(UUID(0, it.toLong()).toString(), "Person $it") }, if (offset < 50) offset + 25 else null)) }
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        repeat(2) {
            controller.state.value.people!!.results.forEach { controller.person(it, true) }; controller.page(true)
        }
        assertEquals(50, controller.state.value.selected.size)
        controller.person(controller.state.value.people!!.results.single(), true)
        controller.person(IdentityPersonDto(F.RECORD, "Foreign"), true)
        assertEquals(50, controller.state.value.selected.size)
        controller.remove(controller.state.value.selected.keys.first()); controller.person(controller.state.value.people!!.results.single(), true)
        assertEquals(50, controller.state.value.selected.size)
    }
    @Test fun operationsAcceptedBeforeCloseDoNotRefreshAnotherLogin() = runBlocking {
        val ops = Operations(); ops.current = F.response()
        val controller = SpeakerIdentificationController(ops, 1); controller.refresh()
        ops.available = false
        controller.decide(F.response().request!!.jobs.single(), true)
        controller.submit(); controller.refresh(); assertEquals(0, ops.changes); assertTrue(ops.decisions.isEmpty())
        controller.close(); assertNull(controller.state.value.people)
    }
}
