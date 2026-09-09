package com.we.meet.feature.docs.util

import kotlinx.coroutines.CancellationException

/** Cancellation is a lifecycle event, not a user-visible network failure. */
inline fun <T> docsRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
