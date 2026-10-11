package com.we.meet.ui.records

import com.we.meet.data.api.*
import com.we.meet.data.api.dto.*
import com.we.meet.data.VoiceprintFixtures as F
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportIdentityControllerTest {
    private class Operations : ImportIdentityOperations {
        var people: suspend (String?, String, Int) -> Result<RecordingImportCandidates> = { org, _, _ -> Result.success(RecordingImportCandidates(org, listOf(IdentityPersonDto(if (org == null) F.OWNER else F.PROFILE, "Synthetic")), null)) }
        var libraries: suspend (Int) -> Result<VoiceprintPageDto<VoiceprintScopeDto>> = { Result.success(VoiceprintPageDto(listOf(VoiceprintScopeDto(F.ORGANIZATION, "Organization", false, VoiceprintPolicyDto(true, 1))), null)) }
        override suspend fun scopes(offset: Int) = libraries(offset)
        override suspend fun candidates(organization: String?, query: String, offset: Int) = people(organization, query, offset)
    }
    private fun controller(ops: Operations) = ImportIdentityController(ops, RecordingImportIdentity(null, emptyList()))
    @Test fun openingLoadsNamesButNeverSelectsAnyoneImplicitly() = runBlocking {
        val c = controller(Operations()); c.scopes(); c.reload()
        assertTrue(c.state.value.ready); assertTrue(c.state.value.selected.isEmpty()); assertTrue(c.state.value.intent.candidateUserIds.isEmpty())
    }
    @Test fun switchingLibraryClearsOldSelectionsAndNames() = runBlocking {
        val c = controller(Operations()); c.scopes(); c.reload(); c.choose(F.OWNER, 2); c.scope(F.ORGANIZATION)
        assertTrue(c.state.value.selected.isEmpty()); assertFalse(c.state.value.names.containsKey(F.OWNER)); assertEquals(F.ORGANIZATION, c.state.value.intent.organizationId)
    }
    @Test fun explicitSelectionsSurviveSearchPagesAndRespectTheBudget() = runBlocking {
        val ops = Operations(); val c = controller(ops); c.scopes(); c.scope(F.ORGANIZATION); c.choose(F.PROFILE, 1)
        ops.people = { org, _, _ -> Result.success(RecordingImportCandidates(org, listOf(IdentityPersonDto(F.OWNER, "Colleague")), 25)) }
        c.query("Colleague"); c.search(); c.choose(F.OWNER, 1)
        assertEquals(setOf(F.PROFILE), c.state.value.selected)
        c.remove(F.PROFILE); c.choose(F.OWNER, 1); assertEquals(listOf(F.OWNER), c.state.value.intent.candidateUserIds)
    }
    @Test fun failedRefreshBlocksReadinessButDoesNotRewriteChosenIDs() = runBlocking {
        val ops = Operations(); val c = controller(ops); c.scopes(); c.reload(); c.choose(F.OWNER, 2)
        ops.people = { _, _, _ -> Result.failure(IllegalStateException()) }; c.reload()
        assertFalse(c.state.value.ready); assertNull(c.state.value.page); assertEquals(listOf(F.OWNER), c.state.value.intent.candidateUserIds)
    }
    @Test fun aLatePageFromThePreviousScopeCannotOverwriteTheNewScope() = runBlocking {
        val ops = Operations(); val c = controller(ops); c.scopes()
        val pending = CompletableDeferred<Result<RecordingImportCandidates>>(); val started = CompletableDeferred<Unit>()
        ops.people = { org, _, _ -> if (org == null) { started.complete(Unit); pending.await() } else Result.success(RecordingImportCandidates(org, emptyList(), null)) }
        val old = async { c.reload() }; started.await(); c.scope(F.ORGANIZATION)
        pending.complete(Result.success(RecordingImportCandidates(null, listOf(IdentityPersonDto(F.OWNER, "Old")), null))); old.await()
        assertEquals(F.ORGANIZATION, c.state.value.page!!.organizationId); assertFalse(c.state.value.names.containsKey(F.OWNER))
    }
    @Test fun closingDiscardsLatePrivateNamesAndSelections() = runBlocking {
        val ops = Operations(); val c = controller(ops); val started = CompletableDeferred<Unit>(); val pending = CompletableDeferred<Result<RecordingImportCandidates>>()
        ops.people = { _, _, _ -> started.complete(Unit); pending.await() }
        val load = async { c.reload() }; started.await(); c.close()
        pending.complete(Result.success(RecordingImportCandidates(null, listOf(IdentityPersonDto(F.OWNER, "Old")), null))); load.await()
        assertTrue(c.state.value.names.isEmpty()); assertTrue(c.state.value.selected.isEmpty()); assertFalse(c.state.value.ready)
    }
    @Test fun sentDeclarationsCannotBeEditedWhileDirectoriesAreReloaded() = runBlocking {
        val c = controller(Operations()); c.scopes(); c.reload(); c.choose(F.OWNER, 2); c.freeze(true)
        c.choose(F.OWNER, 2); c.remove(F.OWNER); c.scope(F.ORGANIZATION); c.query("Change"); c.search()
        assertEquals(RecordingImportIdentity(null, listOf(F.OWNER)), c.state.value.intent); assertEquals("", c.state.value.query)
    }
    @Test fun refreshingKeepsASelectedLibraryFromAnAdditionalPage() = runBlocking {
        val ops = Operations(); val c = controller(ops)
        ops.libraries = { offset -> Result.success(VoiceprintPageDto(if (offset == 0) emptyList() else listOf(VoiceprintScopeDto(F.ORGANIZATION, "Organization", false, VoiceprintPolicyDto(true, 1))), if (offset == 0) 25 else null)) }
        c.scopes(); c.scopes(true); c.scope(F.ORGANIZATION); c.scopes()
        assertEquals(F.ORGANIZATION, c.state.value.organization); assertEquals(F.ORGANIZATION, c.state.value.scopes.single().id)
    }
    @Test fun pausingClearsNamesAndStopsARefreshFromReloadingInTheBackground() = runBlocking {
        val ops = Operations(); val c = controller(ops); c.scopes(); c.reload(); c.choose(F.OWNER, 2)
        var reads = 0
        ops.people = { org, _, _ -> reads++; Result.success(RecordingImportCandidates(org, listOf(IdentityPersonDto(F.OWNER, "Fresh")), null)) }
        val started = CompletableDeferred<Unit>(); val pending = CompletableDeferred<Result<VoiceprintPageDto<VoiceprintScopeDto>>>()
        ops.libraries = { started.complete(Unit); pending.await() }
        val refresh = async { c.scopes(); c.reload() }; started.await(); c.hide()
        pending.complete(Result.success(VoiceprintPageDto(emptyList(), null))); refresh.await()
        assertEquals(0, reads); assertTrue(c.state.value.names.isEmpty()); assertTrue(c.state.value.scopes.isEmpty())
        assertFalse(c.state.value.ready); assertEquals(listOf(F.OWNER), c.state.value.intent.candidateUserIds)
        c.show(); c.reload(); assertEquals(1, reads); assertEquals("Fresh", c.state.value.names[F.OWNER])
    }
}
