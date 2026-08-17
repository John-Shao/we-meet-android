package com.we.meet.feature.im.ui.newchat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Synchronous single-flight guard for picker callbacks that may fire twice. */
internal class ConversationCreationGuard {
    private val active = AtomicBoolean(false)

    fun tryStart(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}

/**
 * Runs one create flow without turning structured-concurrency cancellation into
 * a user-visible business error. Refresh is best-effort: the destination loads
 * the authoritative conversation again, so a refresh failure must not undo a
 * successfully created conversation or prevent navigation.
 */
internal suspend fun <T> runConversationCreation(
    create: suspend () -> T,
    refresh: suspend () -> Unit,
    onReady: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
    onRefreshFailure: (Throwable) -> Unit = {},
) {
    val result = try {
        create()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
        return
    }

    try {
        refresh()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onRefreshFailure(error)
    }

    onReady(result)
}
