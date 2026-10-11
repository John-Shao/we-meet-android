package com.we.meet.ui.voiceprint

import com.we.meet.data.VoiceprintCallFixtures as F
import com.we.meet.data.VoiceprintFixtures
import com.we.meet.data.api.dto.*
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class VoiceprintCallControllerTest {
    private class Operations : VoiceprintCallOperations {
        var valid = true
        var enabled = true
        var value = F.connection()
        var reads = 0
        var checks = 0
        var off = 0
        val declarations = mutableListOf<Triple<Boolean, Boolean, String>>()
        var readResult: suspend () -> Result<VoiceprintCallConnectionDto> = { Result.success(value) }
        var writeResult: suspend (Boolean, Boolean, String) -> Result<VoiceprintCallControlDto> = { paused, shared, device ->
            val state = if (paused) "paused" else if (shared) "shared_microphone" else if (device.isEmpty()) "device_required" else "ready"
            value = value.copy(control = value.control.copy(revision = value.control.revision + 1, paused = paused,
                sharedMicrophone = shared, deviceGroup = device, state = state, runtime = VoiceprintCallRuntimeDto("stopped", state, null)))
            Result.success(value.control)
        }
        override fun allowed() = valid
        override suspend fun capability(): Result<Boolean> { checks++; return Result.success(enabled) }
        override suspend fun read(): Result<VoiceprintCallConnectionDto> { reads++; return readResult() }
        override suspend fun declare(snapshot: VoiceprintCallConnectionDto, paused: Boolean, shared: Boolean, device: String): Result<VoiceprintCallControlDto> {
            declarations += Triple(paused, shared, device); return writeResult(paused, shared, device)
        }
        override suspend fun disableAccumulation(snapshot: VoiceprintCallConnectionDto): Result<VoiceprintSettingsDto> {
            off++; value = value.copy(permission = value.permission.copy(allowAccumulation = false)); return Result.success(VoiceprintFixtures.settings())
        }
    }
    private class Fixture {
        var time = 0L
        val ops = Operations()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = VoiceprintCallController(ops, scope) { time }
        suspend fun start() { controller.start()?.join(); controller.inputObservationAvailable(true) }
        fun close() { controller.close(); scope.cancel() }
    }
    @Test fun openingOnlyReadsAndFeatureOffIsRecheckedAfterThirtySeconds() = runBlocking {
        val f = Fixture(); f.ops.enabled = false; f.start()
        repeat(9) { f.time += 3000; f.controller.refresh()?.join() }
        assertEquals(1, f.ops.checks); assertEquals(0, f.ops.reads); assertTrue(f.ops.declarations.isEmpty())
        f.time = 30000; f.ops.enabled = true; f.controller.refresh()?.join()
        assertEquals(2, f.ops.checks); assertEquals(1, f.ops.reads); assertTrue(f.ops.declarations.isEmpty()); f.close()
    }
    @Test fun featureDisableIsRecheckedWhileEnabledWithoutAnyWrite() = runBlocking {
        val f = Fixture(); f.start(); f.ops.enabled = false; f.time = 30000; f.controller.refresh()?.join()
        assertFalse(f.controller.state.value.enabled); assertNull(f.controller.state.value.snapshot); assertTrue(f.ops.declarations.isEmpty()); f.close()
    }
    @Test fun inputObservationMustBeEstablishedBeforeAnyAllowCommand() = runBlocking {
        val f = Fixture(); f.controller.start()?.join()
        assertEquals("unavailable", f.controller.state.value.phase)
        assertNull(f.controller.declare(false, false, "headset")); assertTrue(f.ops.declarations.isEmpty())
        f.controller.inputObservationAvailable(true); f.controller.declare(false, false, "headset")?.join()
        assertEquals(listOf(Triple(false, false, "headset")), f.ops.declarations); f.close()
    }
    @Test fun positiveProofIncludesRequestTimeAndExpiresBeforeTheConnection() = runBlocking {
        val f = Fixture(); f.ops.readResult = { f.time += 1000; Result.success(F.ready()) }; f.start()
        assertEquals("sampling", f.controller.state.value.phase)
        f.time = 2000; f.controller.tick(); assertEquals("waiting", f.controller.state.value.phase)
        f.time = 15000; f.controller.tick(); assertEquals("unavailable", f.controller.state.value.phase); f.close()
    }
    @Test fun slowReadNeverClaimsSampling() = runBlocking {
        val f = Fixture(); f.ops.readResult = { f.time += 6000; Result.success(F.ready()) }; f.start()
        assertEquals("waiting", f.controller.state.value.phase); f.close()
    }
    @Test fun lostAllowAcknowledgementNeverReplaysTheWrite() = runBlocking {
        val f = Fixture(); f.start(); f.ops.writeResult = { _, _, _ -> Result.failure(IOException("synthetic uncertain")) }
        f.controller.declare(false, false, "headset")?.join(); assertNull(f.controller.state.value.snapshot)
        f.controller.refresh()?.join(); assertEquals(1, f.ops.declarations.size); assertEquals("stopped", f.controller.state.value.phase); f.close()
    }
    @Test fun conflictClearsSnapshotAndRequiresAReadBeforeAnotherAction() = runBlocking {
        val f = Fixture(); f.start(); f.ops.writeResult = { _, _, _ -> Result.failure(HttpException(Response.error<Any>(409, "{}".toResponseBody()))) }
        f.controller.declare(false, false, "headset")?.join()
        assertEquals(VoiceprintCallFailure.CONFLICT, f.controller.state.value.failure); assertNull(f.controller.declare(false, false, "headset"))
        f.controller.refresh()?.join(); assertNull(f.controller.state.value.failure); assertEquals(1, f.ops.declarations.size); f.close()
    }
    @Test fun deviceChangesImmediatelyBlockAndPauseWithAnEmptyDeclaration() = runBlocking {
        val f = Fixture(); f.ops.value = F.ready(); f.start(); val gate = CompletableDeferred<Unit>()
        f.ops.writeResult = { _, _, _ -> gate.await(); Result.failure(IOException()) }
        f.controller.invalidateDevice(); assertEquals("device_changed", f.controller.state.value.phase)
        assertNull(f.controller.declare(false, false, "headset")); assertEquals(listOf(Triple(true, true, "")), f.ops.declarations)
        gate.complete(Unit); yield(); assertNull(f.controller.state.value.snapshot); assertTrue(f.controller.state.value.deviceChanged); f.close()
    }
    @Test fun failedDeviceResetRetriesOnlyAfterALaterSuccessfulRead() = runBlocking {
        val f = Fixture(); f.ops.value = F.ready(); f.start()
        f.ops.writeResult = { _, _, _ -> Result.failure(IOException()) }; f.controller.invalidateDevice()
        assertEquals(1, f.ops.declarations.size); assertNull(f.controller.state.value.snapshot)
        f.controller.tick(); assertEquals(1, f.ops.declarations.size)
        f.controller.refresh()?.join(); assertEquals(2, f.ops.declarations.size); f.close()
    }
    @Test fun untrustedResetReadDoesNotCreateAnImmediateWriteLoop() = runBlocking {
        val f = Fixture(); f.ops.value = F.ready(); f.start()
        f.ops.writeResult = { _, _, _ -> Result.success(f.ops.value.control) }; f.controller.invalidateDevice()
        assertEquals(1, f.ops.declarations.size); assertTrue(f.controller.state.value.deviceChanged)
        f.controller.refresh()?.join(); assertEquals(2, f.ops.declarations.size); f.close()
    }
    @Test fun alreadyUndeclaredDeviceChangesDoNotWrite() = runBlocking {
        val f = Fixture(); f.start(); f.controller.invalidateDevice()
        assertTrue(f.ops.declarations.isEmpty()); assertFalse(f.controller.state.value.deviceChanged); f.close()
    }
    @Test fun deviceChangeDuringAllowQueuesOneSafeResetAfterCompletion() = runBlocking {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<Unit>(); val original = f.ops.writeResult
        f.ops.writeResult = { p, s, d -> if (!p) gate.await(); original(p, s, d) }
        val command = f.controller.declare(false, false, "headset")!!
        f.controller.invalidateDevice(); assertEquals(1, f.ops.declarations.size); gate.complete(Unit); command.join()
        assertEquals(listOf(Triple(false, false, "headset"), Triple(true, true, "")), f.ops.declarations)
        assertFalse(f.controller.state.value.deviceChanged); assertTrue(f.controller.state.value.snapshot!!.control.paused); f.close()
    }
    @Test fun lostInputObservationPausesAndPreventsAllowAfterARefresh() = runBlocking {
        val f = Fixture(); f.ops.value = F.ready(); f.start(); f.controller.inputObservationAvailable(false)
        f.controller.refresh()?.join(); assertEquals("unavailable", f.controller.state.value.phase)
        assertNull(f.controller.declare(false, false, "headset")); assertEquals(listOf(Triple(true, true, "")), f.ops.declarations); f.close()
    }
    @Test fun backgroundClearsPrivateDataAndReturnNeverAllowsAutomatically() = runBlocking {
        val f = Fixture(); f.ops.value = F.ready(); f.start(); f.controller.background()
        assertNull(f.controller.state.value.snapshot); assertFalse(f.controller.state.value.enabled); assertTrue(f.controller.state.value.deviceChanged)
        assertEquals(listOf(Triple(true, true, "")), f.ops.declarations)
        f.start(); assertEquals(2, f.ops.checks); assertTrue(f.controller.state.value.snapshot!!.control.paused)
        assertEquals(1, f.ops.declarations.size); f.close()
    }
    @Test fun loginChangeRejectsLateReadAndClearsPrivateState() = runBlocking {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<Unit>()
        f.ops.readResult = { gate.await(); Result.success(F.ready()) }; val read = f.controller.refresh()!!
        f.ops.valid = false; f.controller.tick(); assertNull(f.controller.state.value.snapshot)
        gate.complete(Unit); read.join(); assertNull(f.controller.state.value.snapshot); assertNull(f.controller.declare(false, false, "headset")); f.close()
    }
    @Test fun pauseSupersedesALateReadAndCloseRejectsLateWrites() = runBlocking {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<Unit>()
        f.ops.readResult = { withContext(NonCancellable) { gate.await() }; Result.success(F.ready()) }
        val read = f.controller.refresh()!!; f.ops.readResult = { Result.success(f.ops.value) }
        f.controller.declare(true, false, "headset")?.join(); gate.complete(Unit); read.join()
        assertTrue(f.controller.state.value.snapshot!!.control.paused)
        val writeGate = CompletableDeferred<Unit>(); f.ops.writeResult = { _, _, _ -> withContext(NonCancellable) { writeGate.await() }; Result.success(F.ready().control) }
        val write = f.controller.declare(false, false, "headset")!!; f.close(); writeGate.complete(Unit); write.join()
        assertNull(f.controller.state.value.snapshot); assertFalse(f.controller.state.value.enabled)
    }
    @Test fun accumulationOffIsExplicitAndFollowedByARead() = runBlocking {
        val f = Fixture(); f.start(); f.controller.disableAccumulation()?.join()
        assertEquals(1, f.ops.off); assertEquals(2, f.ops.reads); assertFalse(f.controller.state.value.snapshot!!.permission.allowAccumulation); f.close()
    }
}
