package com.we.meet.data

import com.we.meet.data.capture.retryPlaybackRead
import com.we.meet.data.capture.playbackFailureReason
import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PlaybackReadRetryTest {
    @Test fun retriesTransientServerFailureButNotDeniedMissingChangedOrRateLimitedReads() = runBlocking {
        for (status in listOf(401, 403, 404, 409, 429, 503)) {
            var attempts = 0
            val error = HttpException(Response.error<Unit>(status, "private content".toResponseBody()))
            assertTrue(runCatching { retryPlaybackRead { attempts++; throw error } }.isFailure)
            assertEquals(if (status == 503) 3 else 1, attempts)
            assertEquals("http_$status", playbackFailureReason(error))
        }
    }
    @Test fun cancellationOrLocalRevocationDuringBackoffDoesNotStartAnotherRead() = runBlocking {
        var attempts = 0
        val job = launch {
            retryPlaybackRead { attempts++; throw IOException("secret-url") }
        }
        yield()
        job.cancelAndJoin()
        assertEquals(1, attempts)
        var allowed = true
        attempts = 0
        assertTrue(runCatching {
            retryPlaybackRead({ check(allowed) }) { attempts++; allowed = false; throw IOException("secret-url") }
        }.isFailure)
        assertEquals(1, attempts)
        assertEquals("network_io", playbackFailureReason(IOException("secret-url")))
    }
    @Test fun integrityValidationFailureIsNeverRetried() = runBlocking {
        var attempts = 0
        assertTrue(runCatching { retryPlaybackRead { attempts++; require(false) } }.isFailure)
        assertEquals(1, attempts)
    }
}
