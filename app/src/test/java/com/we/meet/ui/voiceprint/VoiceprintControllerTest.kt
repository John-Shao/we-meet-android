package com.we.meet.ui.voiceprint

import com.we.meet.data.VoiceprintFixtures as F
import com.we.meet.data.api.dto.*
import com.we.meet.data.repository.VoiceprintPermission
import com.we.meet.data.voiceprint.*
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class VoiceprintControllerTest {
    private class Operations : VoiceprintOperations {
        var allowed = true
        var value = F.settings()
        var registration = F.enrollment()
        var rows = listOf(F.sample())
        var next: Int? = null
        var deletionRows = emptyList<VoiceprintDeletionDto>()
        var settingsRead: suspend () -> Result<VoiceprintSettingsDto> = { Result.success(value) }
        var audioRead: suspend () -> Result<ByteArray> = { Result.success(F.wav()) }
        var uploadResult: suspend () -> Result<VoiceprintSampleDto> = { Result.success(F.sample()) }
        var beginResult: suspend () -> Result<VoiceprintEnrollmentDto> = { Result.success(registration) }
        val starts = mutableListOf<Triple<Int, String, String>>()
        val changes = mutableListOf<Pair<VoiceprintPermission, Boolean>>()
        val uploads = mutableListOf<Pair<Int, ByteArray>>()
        val uploadTokens = mutableListOf<String?>()
        val decisions = mutableListOf<Boolean>()
        val removals = mutableListOf<Triple<String, Int, String>>()
        var readCount = 0
        override fun allowed() = allowed
        override suspend fun settings(): Result<VoiceprintSettingsDto> { readCount++; return settingsRead() }
        override suspend fun samples(offset: Int) = Result.success(VoiceprintPageDto(rows, next))
        override suspend fun deletions(offset: Int) = Result.success(VoiceprintPageDto(deletionRows, null))
        override suspend fun sample(id: String) = Result.success(rows.single { it.id == id })
        override suspend fun enrollment(id: String) = Result.success(registration)
        override suspend fun change(permission: VoiceprintPermission, enabled: Boolean, version: Int): Result<VoiceprintSettingsDto> {
            changes += permission to enabled
            value = value.copy(version = version + 1, allowEnrollment = if (permission == VoiceprintPermission.ENROLLMENT) enabled else value.allowEnrollment,
                allowAccumulation = if (permission == VoiceprintPermission.ACCUMULATION) enabled else value.allowAccumulation,
                allowIdentification = if (permission == VoiceprintPermission.IDENTIFICATION) enabled else value.allowIdentification)
            return Result.success(value)
        }
        override suspend fun policy(enabled: Boolean, version: Int) = Result.success(VoiceprintPolicyDto(enabled, version + 1))
        override suspend fun begin(version: Int, key: String, locale: String): Result<VoiceprintEnrollmentDto> { starts += Triple(version, key, locale); return beginResult() }
        override suspend fun upload(enrollment: VoiceprintEnrollmentDto, slot: Int, bytes: ByteArray): Result<VoiceprintSampleDto> {
            uploads += slot to bytes.copyOf(); uploadTokens += enrollment.uploadToken; return uploadResult()
        }
        override suspend fun audio(sample: VoiceprintSampleDto) = audioRead()
        override suspend fun decide(sample: VoiceprintSampleDto, accepted: Boolean, version: Int): Result<VoiceprintSampleDto> {
            decisions += accepted; rows = listOf(sample.copy(status = if (accepted) "confirmed" else "rejected", confirmable = false, audioAvailable = accepted))
            return Result.success(rows.single())
        }
        override suspend fun remove(profile: String, version: Int, key: String): Result<VoiceprintDeletionDto> {
            removals += Triple(profile, version, key)
            return Result.failure(IOException("uncertain"))
        }
    }
    private class Recording : VoiceprintRecording {
        val result = CompletableDeferred<ByteArray>(); var canceled = 0; var finished = 0
        override suspend fun capture(allowed: () -> Boolean, onStarted: () -> Unit): ByteArray { assertTrue(allowed()); onStarted(); return result.await() }
        override fun finish() { finished++ }
        override fun cancel() { canceled++; result.cancel() }
    }
    private fun failure(code: Int, name: String = "synthetic_error") = HttpException(Response.error<Any>(code, """{"code":"$name"}""".toResponseBody()))
    @Test fun openingNeverEnablesPermissionsStartsRegistrationOrUploads() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops)
        controller.refresh()
        assertNotNull(controller.state.value.settings); assertTrue(ops.changes.isEmpty()); assertTrue(ops.starts.isEmpty()); assertTrue(ops.uploads.isEmpty())
        assertFalse(controller.canRecord()); assertFalse(controller.canUpload())
    }
    @Test fun beginningTheFirstProfileUsesTheServerCreatedProfileAndRetainsItsOriginalKeyOnUnknownResult() = runBlocking {
        val ops = Operations(); ops.rows = emptyList(); ops.value = F.settings().copy(profiles = emptyList())
        val controller = VoiceprintController(ops); controller.refresh()
        ops.beginResult = { Result.failure(IOException("uncertain")) }; controller.begin("en")
        ops.beginResult = { ops.value = F.settings(); Result.success(F.enrollment()) }; controller.begin("zh-CN")
        assertEquals(ops.starts[0], ops.starts[1]); assertEquals("en", ops.starts[1].third)
        assertTrue(controller.canRecord()); assertTrue(ops.uploads.isEmpty())
    }
    @Test fun permissionSwitchesRemainIndependentAndCanBeRevokedWhenUnavailable() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh()
        controller.change(VoiceprintPermission.IDENTIFICATION, true)
        assertTrue(controller.state.value.settings!!.allowIdentification); assertFalse(controller.state.value.settings!!.allowAccumulation)
        ops.value = ops.value.copy(available = false); controller.refresh()
        controller.change(VoiceprintPermission.ACCUMULATION, true); assertEquals(1, ops.changes.size)
        controller.change(VoiceprintPermission.ENROLLMENT, false); assertEquals(2, ops.changes.size); assertFalse(ops.value.allowEnrollment)
    }
    @Test fun choosingAudioRequiresExplicitUploadAndKeepsACopyUntilDiscard() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en")
        val original = F.wav(); controller.file(original); original.fill(0)
        val held = requireNotNull(controller.state.value.clip)
        assertTrue(held.any { it != 0.toByte() }); assertTrue(ops.uploads.isEmpty()); assertTrue(controller.canUpload())
        controller.discard(); assertTrue(held.all { it == 0.toByte() }); assertNull(controller.state.value.clip)
    }
    @Test fun returningFromAnExternalPickerRevalidatesRegistrationWithoutRetainingAudioOrToken() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en"); controller.file(F.wav())
        val bytes = requireNotNull(controller.state.value.clip); val action = requireNotNull(controller.externalAction())
        controller.background()
        assertTrue(bytes.all { it == 0.toByte() }); assertNull(controller.state.value.enrollment)
        controller.resume(); assertTrue(controller.restoreExternalAction(action))
        assertEquals(F.ENROLLMENT, controller.state.value.enrollment!!.id)
        assertNull(controller.state.value.clip); assertTrue(ops.uploads.isEmpty()); assertEquals(1, ops.starts.size)
    }
    @Test fun externalResultsCannotRestoreChangedConsentExpiredClosedOrDifferentRegistrations() = runBlocking {
        for (change in 0..4) {
            val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en")
            val action = requireNotNull(controller.externalAction()); controller.background(); controller.resume()
            when (change) {
                0 -> ops.value = ops.value.copy(version = ops.value.version + 1)
                1 -> ops.value = ops.value.copy(generation = ops.value.generation + 1)
                2 -> ops.registration = ops.registration.copy(expiresAt = "2000-01-01T00:00:00Z")
                3 -> ops.registration = ops.registration.copy(status = "closed", uploadToken = null)
                4 -> ops.registration = ops.registration.copy(id = F.KEY)
            }
            assertFalse(controller.restoreExternalAction(action)); assertFalse(controller.canRecord()); assertTrue(ops.uploads.isEmpty())
        }
    }
    @Test fun theLastUncertainUploadRetriesItsOriginalSlotWavAndTokenAfterEnrollmentCloses() = runBlocking {
        val ops = Operations(); ops.registration = F.enrollment().copy(uploadedSlots = (0..4).toList())
        val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en"); controller.file(F.wav())
        ops.uploadResult = { Result.failure(IOException("uncertain")) }; controller.upload()
        ops.registration = ops.registration.copy(status = "closed", uploadedSlots = (0..5).toList(), uploadToken = null)
        controller.refresh(); assertFalse(controller.canRecord()); assertTrue(controller.canUpload())
        ops.uploadResult = { Result.success(F.sample()) }; controller.upload()
        assertEquals(listOf(5, 5), ops.uploads.map { it.first }); assertArrayEquals(ops.uploads[0].second, ops.uploads[1].second)
        assertEquals(listOf(F.token, F.token), ops.uploadTokens); assertNull(controller.state.value.clip)
    }
    @Test fun backgroundPurgesAllPrivateContentAndFencesALateAudioResponse() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en"); controller.file(F.wav())
        val held = requireNotNull(controller.state.value.clip)
        val pending = CompletableDeferred<Result<ByteArray>>(); ops.audioRead = { pending.await() }
        val loading = async(start = CoroutineStart.UNDISPATCHED) { controller.preview(F.sample()) }
        controller.background(); val late = F.wav(); pending.complete(Result.success(late)); loading.await()
        assertTrue(held.all { it == 0.toByte() }); assertTrue(late.all { it == 0.toByte() }); assertNull(controller.state.value.preview)
        assertNull(controller.state.value.settings); assertNull(controller.state.value.enrollment)
        controller.resume(); controller.refresh(); assertNotNull(controller.state.value.settings)
    }
    @Test fun cannotConfirmQualityPendingOrUnlistenedOrNotSelfConfirmedSamples() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.preview(F.sample())
        controller.listened(F.SAMPLE); controller.confirmSelf(F.SAMPLE, true); controller.decide(F.sample(), true); assertTrue(ops.decisions.isEmpty())
        ops.rows = listOf(F.sample().copy(status = "ready", confirmable = true)); controller.refresh(); controller.preview(ops.rows.single())
        controller.decide(ops.rows.single(), true); assertTrue(ops.decisions.isEmpty())
        controller.listened(F.SAMPLE); controller.decide(ops.rows.single(), true); assertTrue(ops.decisions.isEmpty())
        controller.confirmSelf(F.SAMPLE, true); controller.decide(ops.rows.single(), true); assertEquals(listOf(true), ops.decisions)
        assertNull(controller.state.value.preview)
    }
    @Test fun rejectionRequiresNoFalseIdentityAssertionAndClearsTheAudio() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.preview(F.sample())
        val bytes = requireNotNull(controller.state.value.preview).bytes
        controller.decide(F.sample(), false)
        assertEquals(listOf(false), ops.decisions); assertTrue(bytes.all { it == 0.toByte() }); assertNull(controller.state.value.preview)
    }
    @Test fun expiredAuthorizationDropsAudioAndUploadAuthority() = runBlocking {
        val ops = Operations(); var time = 0L; val controller = VoiceprintController(ops, now = { time })
        controller.refresh(); controller.begin("en"); controller.file(F.wav()); controller.preview(F.sample())
        val local = requireNotNull(controller.state.value.clip); val remote = requireNotNull(controller.state.value.preview).bytes
        time = Long.MAX_VALUE; controller.tick()
        assertTrue(local.all { it == 0.toByte() }); assertTrue(remote.all { it == 0.toByte() }); assertFalse(controller.canRecord()); assertNull(controller.state.value.enrollment)
    }
    @Test fun generationChangesDuringPaginationDiscardOldPagesAudioAndCommands() = runBlocking {
        val ops = Operations(); ops.next = 25
        val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en"); controller.file(F.wav())
        val bytes = requireNotNull(controller.state.value.clip)
        ops.value = ops.value.copy(version = 2, generation = 2, profiles = emptyList()); ops.rows = emptyList()
        controller.more(true)
        assertTrue(controller.state.value.samples.isEmpty()); assertNull(controller.state.value.enrollment); assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun profileDeletionUsesTheOriginalVersionAndKeyForAnExplicitRetry() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh()
        controller.remove(); assertTrue(ops.removals.isEmpty())
        controller.requestRemoval(F.PROFILE); controller.remove(); controller.remove()
        assertEquals(2, ops.removals.size); assertEquals(ops.removals[0], ops.removals[1]); assertEquals(1, ops.removals[0].second)
    }
    @Test fun conflictsHidePreviewAndRequireManualRefreshBeforeFurtherWrites() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.preview(F.sample())
        val bytes = requireNotNull(controller.state.value.preview).bytes
        ops.settingsRead = { Result.failure(failure(409)) }; controller.refresh()
        assertTrue(controller.state.value.conflict); assertTrue(bytes.all { it == 0.toByte() }); assertNull(controller.state.value.preview)
        controller.begin("en"); assertTrue(ops.starts.isEmpty())
        ops.settingsRead = { Result.success(ops.value) }; controller.refresh(true); assertFalse(controller.state.value.conflict)
    }
    @Test fun accountSwitchAndClosingStopRecordingAndRejectLateResults() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en")
        val source = Recording(); val recording = async(start = CoroutineStart.UNDISPATCHED) { controller.record(source) }
        yield(); ops.allowed = false; controller.tick(); recording.join()
        assertTrue(source.canceled > 0); assertNull(controller.state.value.clip); assertNull(controller.state.value.settings)
        controller.refresh(); assertEquals(2, ops.readCount)
    }
    @Test fun aPermissionHeartbeatCanCancelAnActiveRecordingBeforeItFinishes() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh(); controller.begin("en")
        val source = Recording(); val recording = async(start = CoroutineStart.UNDISPATCHED) { controller.record(source) }
        yield(); assertEquals(VoiceprintPhase.RECORDING, controller.state.value.phase)
        ops.value = ops.value.copy(version = 2, allowEnrollment = false)
        controller.refresh(); recording.join()
        assertTrue(source.canceled > 0); assertNull(controller.state.value.clip); assertNull(controller.state.value.enrollment); assertFalse(controller.canRecord())
    }
    @Test fun publicErrorMessagesUseOnlyAnAllowlistAndNeverRawServerText() = runBlocking {
        val ops = Operations(); val controller = VoiceprintController(ops); controller.refresh()
        ops.beginResult = { Result.failure(failure(429, "voiceprint_enrollment_quota")) }; controller.begin("en")
        assertEquals(VoiceprintFailure.QUOTA, controller.state.value.failure)
        ops.beginResult = { Result.failure(failure(500, "synthetic_private_payload")) }; controller.begin("en")
        assertEquals(VoiceprintFailure.REQUEST, controller.state.value.failure)
    }

    @Test fun audioExpiringDuringTheRequestIsErasedBeforePublication() = runBlocking {
        val ops = Operations(); var time = 0L; val controller = VoiceprintController(ops, now = { time })
        controller.refresh()
        val bytes = F.wav()
        ops.audioRead = { time = Long.MAX_VALUE; Result.success(bytes) }
        controller.preview(F.sample())
        assertNull(controller.state.value.preview); assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun refreshedAudioExpiryShortensTheExistingPlaybackLease() = runBlocking {
        val ops = Operations(); var time = 0L; val controller = VoiceprintController(ops, now = { time })
        controller.refresh(); controller.preview(F.sample())
        val bytes = requireNotNull(controller.state.value.preview).bytes
        ops.rows = listOf(F.sample().copy(expiresAt = "1970-01-01T00:00:01Z"))
        controller.refresh(); time = 1000; controller.tick()
        assertNull(controller.state.value.preview); assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun accessRevocationClearsPrivateDeletionReceiptsAsWellAsSettings() = runBlocking {
        val ops = Operations(); ops.deletionRows = listOf(F.deletion())
        val controller = VoiceprintController(ops); controller.refresh()
        assertEquals(1, controller.state.value.deletions.size)
        ops.settingsRead = { Result.failure(failure(403)) }; controller.refresh()
        assertNull(controller.state.value.settings); assertTrue(controller.state.value.samples.isEmpty())
        assertTrue(controller.state.value.deletions.isEmpty()); assertNull(controller.state.value.deletionOffset)
    }
}
