package com.we.meet.feature.assistant.aicall.vm

import com.we.meet.feature.assistant.aicall.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraActionControllerTest {
    private class Fixture {
        var active = true; var camera: Boolean? = false; var available = true
        var visible = true; var granted = true; var unsafe = false; var pending = false
        var permission: CameraPermissionRequest? = null
        var media: suspend (Boolean) -> Unit = { camera = it }
        var foreground: suspend (Boolean) -> Unit = {}
        val operations = mutableListOf<String>()
        val controller = CameraActionController({ active }, { camera }, { available }, { visible }, { granted },
            { permission = it }, { operations += "foreground:$it"; foreground(it) }, { operations += "media:$it"; media(it) },
            { camera = it }, { pending = it }, { unsafe = true; active = false },
            { code, enabled, changed -> CameraActionResult(code in setOf("enabled", "disabled", "already_enabled", "already_disabled"), enabled, changed, code, code) })
        suspend fun set(value: Boolean) = controller.requestCameraEnabled(value, CameraActionSource.Voice)
    }

    @Test fun fourStatesAndQueryUseActualStateWithoutTogglingOrReopening() = runTest {
        val f = Fixture()
        assertEquals("already_disabled", f.set(false).code)
        assertEquals("enabled", f.set(true).code)
        assertEquals("already_enabled", f.set(true).code)
        assertEquals(true, f.controller.query().enabled)
        assertEquals("disabled", f.set(false).code)
        assertEquals(listOf("foreground:true", "media:true", "media:false", "foreground:false"), f.operations)
        assertFalse(f.pending)
    }

    @Test fun permissionIsAwaitedAndGrantedBeforeHardware() = runTest {
        val f = Fixture(); f.granted = false
        val action = async { f.set(true) }; runCurrent()
        assertTrue(f.pending); assertTrue(f.operations.isEmpty())
        f.granted = true; f.controller.permissionResult(f.permission!!.id, true); runCurrent()
        assertEquals("enabled", action.await().code); assertNull(f.permission)
    }

    @Test fun denialAndPermissionTimeoutNeverOpenAndLateGrantIsIgnored() = runTest {
        val f = Fixture(); f.granted = false
        val denied = async { f.set(true) }; runCurrent()
        f.controller.permissionResult(f.permission!!.id, false); runCurrent()
        assertEquals("permission_denied", denied.await().code)
        val timeout = async { f.set(true) }; runCurrent(); val id = f.permission!!.id
        advanceTimeBy(60_001); runCurrent(); assertEquals("timeout", timeout.await().code)
        f.granted = true; f.controller.permissionResult(id, true)
        assertTrue(f.operations.isEmpty()); assertFalse(f.camera!!); assertFalse(f.pending)
    }

    @Test fun closeSupersedesPendingPermissionAndSubsequentGrantCannotOpen() = runTest {
        val f = Fixture(); f.granted = false
        val open = async { f.set(true) }; runCurrent(); val id = f.permission!!.id
        val close = async { f.set(false) }; runCurrent()
        assertEquals("cancelled", open.await().code); assertEquals("already_disabled", close.await().code)
        f.granted = true; f.controller.permissionResult(id, true); assertTrue(f.operations.isEmpty())
    }

    @Test fun backgroundCanCloseButCannotOpenAndUnsupportedVideoNeverRequestsPermission() = runTest {
        val f = Fixture(); f.visible = false
        assertEquals("foreground_required", f.set(true).code)
        f.camera = true; assertEquals("disabled", f.set(false).code)
        f.visible = true; f.available = false; f.granted = false
        assertEquals("video_unavailable", f.set(true).code); assertNull(f.permission)
    }

    @Test fun openFailureAndFirstFrameTimeoutCleanCaptureBeforeReportingOff() = runTest {
        val f = Fixture(); f.media = { if (it) { f.camera = null; delay(20_000) } else f.camera = false }
        val action = async { f.set(true) }; runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertEquals("timeout", action.await().code)
        assertEquals(false, f.camera); assertFalse(f.unsafe)
        assertEquals(listOf("foreground:true", "media:true", "media:false", "foreground:false"), f.operations)
    }

    @Test fun failedStopCannotClaimSuccessAndClosesOwner() = runTest {
        val f = Fixture(); f.camera = true; f.media = { error("Device cannot stop") }
        val result = f.set(false)
        assertFalse(result.success); assertNull(result.enabled); assertTrue(f.unsafe)
    }

    @Test fun interruptionWhileOpeningCleansHardwareAndNeverLeavesPendingUi() = runTest {
        val f = Fixture(); f.media = { if (it) { f.camera = null; awaitCancellation() } else f.camera = false }
        val action = launch { f.set(true) }; runCurrent(); action.cancel(); runCurrent()
        assertEquals(false, f.camera); assertFalse(f.pending); assertFalse(f.unsafe)
    }

    @Test fun closedOwnerRejectsQueuedCommandsAndClearsPermission() = runTest {
        val f = Fixture(); f.granted = false
        val action = async { f.set(true) }; runCurrent(); val id = f.permission!!.id
        f.controller.close(); runCurrent(); assertEquals("cancelled", action.await().code)
        f.granted = true; f.controller.permissionResult(id, true)
        assertEquals("cancelled", f.set(true).code); assertTrue(f.operations.isEmpty())
    }

    @Test fun buttonAndVoiceCommandsSerializeThroughTheSameHardwareOwner() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>()
        f.media = { if (it) gate.await(); f.camera = it }
        val open = async { f.controller.requestCameraEnabled(true, CameraActionSource.Button) }
        val close = async { f.set(false) }; runCurrent()
        assertEquals(listOf("foreground:true", "media:true"), f.operations)
        gate.complete(Unit); runCurrent(); assertTrue(open.await().success); assertTrue(close.await().success)
        assertEquals(false, f.camera)
    }

    @Test fun backgroundDuringForegroundServiceUpgradeDoesNotStartCapture() = runTest {
        val f = Fixture(); f.foreground = { if (it) f.visible = false }
        val result = f.set(true)
        assertEquals("foreground_required", result.code); assertFalse(result.success)
        assertEquals(false, result.enabled); assertFalse(f.unsafe)
        assertFalse(f.operations.contains("media:true"))
    }

    @Test fun flipSharesTheLockWithVoiceCloseAndHasItsOwnTimeout() = runTest {
        val f = Fixture(); f.camera = true
        val flip = async { runCatching { f.controller.flip { delay(20_000); true } } }
        runCurrent(); val close = async { f.set(false) }; runCurrent()
        assertTrue(f.operations.isEmpty()); assertTrue(f.pending)
        advanceTimeBy(10_001); runCurrent()
        assertTrue(flip.await().exceptionOrNull() is TimeoutCancellationException)
        assertEquals("disabled", close.await().code); assertFalse(f.pending)
    }
}
