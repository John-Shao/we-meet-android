package com.we.meet.ui.records

import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.RecordSourceChangedException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SpeakerIdentityControllerTest {
    private val member = RecordSpeakerContactDto("member:one", "member", "Ada", departmentName = "Design")
    private val external = RecordSpeakerContactDto("external:two", "external", "Ada", organizationName = "Partner")
    private val department = RecordSpeakerContactDto("dept-one", "department", "Design", departmentId = "dept-one")
    private val updated = RecordSpeakerDto("speaker", "Speaker 1", "diarized", recordRevision = 4)
    private data class Read(val query: String?, val kind: String, val departmentId: String?, val offset: Int)
    private inner class Operations : SpeakerIdentityOperations {
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<RecordIdentityDecisionRequest>()
        var read: suspend (Read) -> Result<RecordSpeakerContactPageDto> = { Result.success(RecordSpeakerContactPageDto(listOf(member, external))) }
        var write: suspend (RecordIdentityDecisionRequest) -> Result<RecordSpeakerDto> = { Result.success(updated) }
        override suspend fun contacts(query: String?, kind: String, departmentId: String?, offset: Int): Result<RecordSpeakerContactPageDto> {
            val request = Read(query, kind, departmentId, offset)
            reads += request
            return read(request)
        }
        override suspend fun decide(request: RecordIdentityDecisionRequest): Result<RecordSpeakerDto> { writes += request; return write(request) }
    }

    @Test fun memberAndExternalAreExplicitSelectionsWithTheOpenedRevision() = runBlocking {
        val operations = Operations()
        val editor = SpeakerIdentityController(operations, 3)
        editor.reload()
        editor.choose(member)
        assertTrue(operations.writes.isEmpty())
        assertTrue(editor.save())
        assertEquals(RecordIdentityDecisionRequest("select_contact", 3, contactRef = member.ref), operations.writes.single())
        editor.choose(external)
        assertTrue(editor.save())
        assertEquals(external.ref, operations.writes.last().contactRef)
        assertNull(operations.writes.last().label)
    }

    @Test fun labelIsTrimmedAndClearHasNoConflictingFields() = runBlocking {
        val operations = Operations()
        val editor = SpeakerIdentityController(operations, 3)
        editor.mode(SpeakerIdentityMode.LABEL)
        editor.editLabel("  Guest Host  ")
        assertTrue(editor.save())
        assertEquals(RecordIdentityDecisionRequest("set_label", 3, label = "Guest Host"), operations.writes.single())
        assertTrue(editor.save(clear = true))
        assertEquals(RecordIdentityDecisionRequest("clear", 3), operations.writes.last())
    }

    @Test fun switchingModesDiscardsContactAndNeverMixesItWithALabel() = runBlocking {
        val operations = Operations()
        val editor = SpeakerIdentityController(operations, 3)
        editor.reload(); editor.choose(external)
        editor.mode(SpeakerIdentityMode.LABEL); editor.editLabel("Host")
        assertNull(editor.state.value.selected)
        editor.mode(SpeakerIdentityMode.CONTACTS)
        assertFalse(editor.state.value.canSave)
        assertFalse(editor.save())
        assertTrue(operations.writes.isEmpty())
    }

    @Test fun invalidAndOverlongLabelsNeverSendBut64UnicodeCodePointsAreAllowed() = runBlocking {
        val operations = Operations()
        val editor = SpeakerIdentityController(operations, 3, initialLabel = "Guest")
        for (label in listOf("", "  ", "x".repeat(65), "Guest\nHost", "\nHost", "Host\t", "\u0000Host", "\u2028Host", "Host\u2029", "\u2028", "Host\u200B", "Host\uD800", "Host" + String(Character.toChars(0xE0001)))) {
            editor.editLabel(label)
            assertFalse(editor.state.value.canSave)
            assertFalse(editor.save())
        }
        assertTrue(operations.writes.isEmpty())
        val unicode = String(Character.toChars(0x1F600)).repeat(64)
        editor.editLabel(unicode)
        assertTrue(editor.save())
        assertEquals(unicode, operations.writes.single().label)
    }

    @Test fun searchingAndPagingDiscardThePreviousSelection() = runBlocking {
        val operations = Operations().apply {
            read = { Result.success(RecordSpeakerContactPageDto(listOf(member), if (it.offset == 0) 25 else null)) }
        }
        val editor = SpeakerIdentityController(operations, 3)
        editor.reload(); editor.choose(member)
        editor.editQuery(" Ada "); editor.search()
        assertEquals(Read("Ada", "all", null, 0), operations.reads.last())
        assertNull(editor.state.value.selected)
        editor.choose(member); editor.page(true)
        assertEquals(25, operations.reads.last().offset)
        assertNull(editor.state.value.selected)
        assertFalse(editor.save())
        editor.page(false)
        assertEquals(0, operations.reads.last().offset)
    }

    @Test fun departmentNavigationUsesItsIdAndClearsTheDepartmentSearch() = runBlocking {
        val operations = Operations().apply { read = { Result.success(RecordSpeakerContactPageDto(listOf(if (it.kind == "departments") department else member))) } }
        val editor = SpeakerIdentityController(operations, 3)
        editor.filter("departments"); editor.editQuery("Design"); editor.search()
        editor.choose(department)
        assertEquals(Read(null, "member", department.ref, 0), operations.reads.last())
        assertEquals(department, editor.state.value.department)
        assertTrue(operations.writes.isEmpty())
        editor.filter("external")
        assertNull(editor.state.value.department)
        assertEquals(Read(null, "external", null, 0), operations.reads.last())
    }

    @Test fun aLateDirectoryResponseCannotOverwriteANewerFilter() = runBlocking {
        val pending = CompletableDeferred<Result<RecordSpeakerContactPageDto>>()
        val operations = Operations().apply { read = { if (it.kind == "all") pending.await() else Result.success(RecordSpeakerContactPageDto(listOf(external))) } }
        val editor = SpeakerIdentityController(operations, 3)
        val old = async(start = CoroutineStart.UNDISPATCHED) { editor.reload() }
        editor.filter("external")
        pending.complete(Result.success(RecordSpeakerContactPageDto(listOf(member))))
        old.await()
        assertEquals(listOf(external), editor.state.value.page.results)
        assertEquals("external", editor.state.value.kind)
    }

    @Test fun closingDiscardsPrivateDirectoryDataAndLateResults() = runBlocking {
        val pending = CompletableDeferred<Result<RecordSpeakerContactPageDto>>()
        val operations = Operations().apply { read = { pending.await() } }
        val editor = SpeakerIdentityController(operations, 3)
        val old = async(start = CoroutineStart.UNDISPATCHED) { editor.reload() }
        editor.close()
        pending.complete(Result.success(RecordSpeakerContactPageDto(listOf(external))))
        old.await()
        assertTrue(editor.state.value.page.results.isEmpty())
        editor.editLabel("old account draft")
        assertFalse(editor.save(clear = true))
        assertTrue(operations.writes.isEmpty())
    }

    @Test fun concurrentSaveAndClearSendOnlyOneRequest() = runBlocking {
        val pending = CompletableDeferred<Result<RecordSpeakerDto>>()
        val operations = Operations().apply { write = { pending.await() } }
        val editor = SpeakerIdentityController(operations, 3, "Host")
        val first = async(start = CoroutineStart.UNDISPATCHED) { editor.save() }
        assertFalse(editor.save(clear = true)); assertFalse(editor.save())
        editor.editLabel("Another")
        assertEquals("Host", editor.state.value.label)
        pending.complete(Result.success(updated))
        assertTrue(first.await())
        assertEquals(1, operations.writes.size)
    }

    @Test fun conflictKeepsDraftAndBlocksEveryFurtherWriteUntilReopening() = runBlocking {
        for (error in listOf(RecordSourceChangedException(), HttpException(Response.error<Any>(409, "{}".toResponseBody())))) {
            val operations = Operations().apply { write = { Result.failure(error) } }
            val editor = SpeakerIdentityController(operations, 3, "Guest")
            assertFalse(editor.save())
            assertEquals(SpeakerIdentityFailure.CONFLICT, editor.state.value.failure)
            assertEquals("Guest", editor.state.value.label)
            assertFalse(editor.save(clear = true)); assertFalse(editor.save())
            editor.mode(SpeakerIdentityMode.CONTACTS)
            assertEquals(1, operations.writes.size)
        }
    }

    @Test fun anObservedNewRecordRevisionBlocksStaleWritesAndLateDirectoryRead() = runBlocking {
        val pending = CompletableDeferred<Result<RecordSpeakerContactPageDto>>()
        val operations = Operations().apply { read = { pending.await() } }
        val editor = SpeakerIdentityController(operations, 3)
        val old = async(start = CoroutineStart.UNDISPATCHED) { editor.reload() }
        editor.revisionChanged(4)
        pending.complete(Result.success(RecordSpeakerContactPageDto(listOf(member))))
        old.await()
        assertEquals(SpeakerIdentityFailure.CONFLICT, editor.state.value.failure)
        assertTrue(editor.state.value.page.results.isEmpty())
        assertFalse(editor.save(clear = true))
    }

    @Test fun transientSaveFailureCanRetryButPermissionLossCannot() = runBlocking {
        val operations = Operations().apply { write = { Result.failure(IOException("private upstream text")) } }
        val editor = SpeakerIdentityController(operations, 3, "Host")
        assertFalse(editor.save())
        assertEquals(SpeakerIdentityFailure.SAVE, editor.state.value.failure)
        operations.write = { Result.failure(HttpException(Response.error<Any>(403, "{}".toResponseBody()))) }
        assertFalse(editor.save())
        assertEquals(SpeakerIdentityFailure.ACCESS, editor.state.value.failure)
        assertFalse(editor.save(clear = true))
        assertEquals(2, operations.writes.size)
    }

    @Test fun aLateSaveFailureNeverClearsAnAlreadyObservedConflict() = runBlocking {
        val pending = CompletableDeferred<Result<RecordSpeakerDto>>()
        val operations = Operations().apply { write = { pending.await() } }
        val editor = SpeakerIdentityController(operations, 3, "Guest")
        val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.save() }
        editor.revisionChanged(4)
        pending.complete(Result.failure(IOException("late transport failure")))
        assertFalse(saving.await())
        assertEquals(SpeakerIdentityFailure.CONFLICT, editor.state.value.failure)
        assertEquals("Guest", editor.state.value.label)
        assertFalse(editor.save())
        assertEquals(1, operations.writes.size)
    }
}
