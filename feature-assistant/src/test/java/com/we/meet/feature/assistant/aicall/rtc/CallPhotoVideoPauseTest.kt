package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.PhotoCleanupException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallPhotoVideoPauseTest {
    @Test fun videoReturnsExactImageOnlyAfterCameraIsRestored() = runTest {
        val events = mutableListOf<String>(); val bytes = byteArrayOf(1, 2)
        val result = withPhotoVideoPause(true, { true }, { events += "pause" }, { events += "resume" }) { events += "capture"; bytes }
        assertSame(bytes, result); assertEquals(listOf("pause", "capture", "resume"), events)
    }

    @Test fun voiceDoesNotOpenModelVideoAndHangupDoesNotRestartCamera() = runTest {
        var resumes = 0
        withPhotoVideoPause(false, { true }, { error("Must not pause") }, { resumes++ }) { byteArrayOf(1) }
        var current = true
        withPhotoVideoPause(true, { current }, {}, { resumes++ }) { current = false; byteArrayOf(2) }
        assertEquals(0, resumes)
    }

    @Test fun ordinaryCaptureFailureAndInterruptionRestoreVideo() = runTest {
        var resumes = 0
        try { withPhotoVideoPause(true, { true }, {}, { resumes++ }) { error("Capture failed") }; fail() }
        catch (_: IllegalStateException) { }
        val job = launch { withPhotoVideoPause(true, { true }, {}, { resumes++ }) { awaitCancellation() } }
        runCurrent(); job.cancelAndJoin()
        assertEquals(2, resumes)
    }

    @Test fun uncertainCleanupNeverReopensAndRestartFailureIsUnsafe() = runTest {
        var resumes = 0
        try { withPhotoVideoPause(true, { true }, {}, { resumes++ }) { throw PhotoCleanupException(IllegalStateException()) }; fail() }
        catch (_: PhotoCleanupException) { }
        assertEquals(0, resumes)
        try { withPhotoVideoPause(true, { true }, {}, { error("Resume failed") }) { byteArrayOf(1) }; fail() }
        catch (_: PhotoCleanupException) { }
    }
}
