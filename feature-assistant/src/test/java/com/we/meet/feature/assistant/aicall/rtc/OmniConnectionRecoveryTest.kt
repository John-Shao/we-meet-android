package com.we.meet.feature.assistant.aicall.rtc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OmniConnectionRecoveryTest {
    @Test fun recoveredConnectionSurvivesOriginalDeadline() = runTest {
        var failures = 0
        val recovery = OmniConnectionRecovery(this) { failures++ }
        recovery.disconnected()
        advanceTimeBy(9_000)
        recovery.connected()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, failures)
    }

    @Test fun duplicateCallbacksDoNotExtendDeadline() = runTest {
        var failures = 0
        val recovery = OmniConnectionRecovery(this) { failures++ }
        recovery.disconnected()
        advanceTimeBy(9_000)
        recovery.disconnected()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, failures)
        recovery.disconnected()
        advanceTimeBy(20_000)
        assertEquals(1, failures)
    }

    @Test fun laterOutageGetsANewRecoveryWindow() = runTest {
        var failures = 0
        val recovery = OmniConnectionRecovery(this) { failures++ }
        recovery.disconnected()
        advanceTimeBy(5_000)
        recovery.connected()
        recovery.disconnected()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, failures)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures)
    }

    @Test fun hangupPreventsPendingAndLateTimeouts() = runTest {
        var failures = 0
        val recovery = OmniConnectionRecovery(this) { failures++ }
        recovery.disconnected()
        advanceTimeBy(5_000)
        recovery.close()
        recovery.disconnected()
        advanceTimeBy(20_000)
        assertEquals(0, failures)
    }
}
