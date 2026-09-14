package com.we.meet.data.capture

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException

/** Only idempotent playback reads; never retry permission, source or integrity failures. */
internal suspend fun <T> retryPlaybackRead(guard: () -> Unit = {}, read: suspend () -> T): T {
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        guard()
        try {
            return read()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val transient = error is IOException || error is TimeoutCancellationException ||
                (error is HttpException && error.code() in setOf(408, 500, 502, 503, 504))
            if (!transient || attempt == 2) throw error
            delay(250L * (attempt + 1))
        }
    }
    error("Unreachable playback retry")
}

/** Fixed categories only: no exception text, URLs, audio, tokens or record identifiers. */
internal fun playbackFailureReason(error: Exception): String = when (error) {
    is TimeoutCancellationException -> "access_timeout"
    is CancellationException -> "canceled"
    is HttpException -> "http_${error.code()}"
    is IOException -> "network_io"
    is IllegalArgumentException -> "source_validation"
    else -> "playback_unavailable"
}
