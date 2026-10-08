package com.we.meet.feature.assistant.aicall.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAILeaseTest {
    private val info = DirectAILeaseInfo(UUID.randomUUID().toString(), 120, 30, enforce = true)

    @Test fun heartbeatsStopAndCloseIsSentExactlyOnce() = runTest {
        val sent = mutableListOf<String>()
        val lease = DirectAILease(info, { id, operation -> assertEquals(info.id, id); sent += operation.operation },
            { fail("Unexpected loss") }, StandardTestDispatcher(testScheduler))
        lease.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("heartbeat", "heartbeat"), sent)
        lease.close(); lease.close(); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(listOf("heartbeat", "heartbeat", "close"), sent)
    }

    @Test fun repeatedNetworkFailuresEndTheClientAndDoNotRestartTheModel() = runTest {
        var lost = 0
        val sent = mutableListOf<String>()
        val lease = DirectAILease(info, { _, operation ->
            sent += operation.operation
            if (operation.operation == "heartbeat") throw IOException("offline")
        }, { lost++ }, StandardTestDispatcher(testScheduler))
        lease.start(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, lost)
        assertEquals(listOf("heartbeat", "heartbeat", "heartbeat", "close"), sent)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, lost)
    }

    @Test fun transientFailureRecoversWithoutEndingTheAudioSession() = runTest {
        var attempts = 0
        val lease = DirectAILease(info, { _, operation ->
            if (operation.operation == "heartbeat" && ++attempts <= 2) throw IOException()
        }, { fail("Transient network failure should recover") }, StandardTestDispatcher(testScheduler))
        lease.start(); runCurrent(); advanceTimeBy(90_000); runCurrent()
        assertEquals(4, attempts)
        lease.close(); runCurrent()
    }

    @Test fun expiredOrRevokedLeaseStopsImmediately() = runTest {
        for (status in listOf(401, 403, 404, 410)) {
            var lost = 0
            var heartbeats = 0
            val lease = DirectAILease(info, { _, operation ->
                if (operation.operation == "heartbeat") {
                    heartbeats++
                    throw HttpException(Response.error<Unit>(status, "{}".toResponseBody()))
                }
            }, { lost++ }, StandardTestDispatcher(testScheduler))
            lease.start(); runCurrent()
            assertEquals(1, lost); assertEquals(1, heartbeats)
        }
    }

    @Test fun stoppingDuringHeartbeatCancelsItAndStillAttemptsRelease() = runTest {
        val sent = mutableListOf<String>()
        val lease = DirectAILease(info, { _, operation ->
            sent += operation.operation
            if (operation.operation == "heartbeat") kotlinx.coroutines.awaitCancellation()
        }, { fail("Owner stopped the session") }, StandardTestDispatcher(testScheduler))
        lease.start(); runCurrent(); lease.close(); runCurrent()
        assertEquals(listOf("heartbeat", "close"), sent)
    }

    @Test fun unsafeMetadataIsRejectedAndNeverPrinted() {
        assertFalse(info.toString().contains(info.id))
        for (bad in listOf(info.copy(id = "not-an-id"), info.copy(heartbeatSeconds = 1), info.copy(ttlSeconds = 60))) {
            assertTrue(runCatching { DirectAILease(bad, { _, _ -> }, {}) }.isFailure)
        }
    }
    @Test fun observationFailureDoesNotInterruptWorkingDirectAudio() = runTest {
        var released = false
        val lease = DirectAILease(info.copy(enforce = false), { _, operation ->
            if (operation.operation == "heartbeat") throw IOException()
            released = true
        }, { fail("Observation cannot stop media") }, StandardTestDispatcher(testScheduler))
        lease.start(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertFalse("Lost observation must not claim the vendor audio ended", released)
    }
}
