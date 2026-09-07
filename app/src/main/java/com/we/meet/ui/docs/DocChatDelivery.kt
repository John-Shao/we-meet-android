package com.we.meet.ui.docs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Track delivery separately from access so retries never resend confirmed cards. */
internal data class DocChatDelivery(
    val pending: Set<String>,
    val delivered: Set<String> = emptySet(),
    val awaitingAccess: Set<String> = emptySet(),
) {
    val complete: Boolean get() = pending.isEmpty() && awaitingAccess.isEmpty() && delivered.isNotEmpty()
}

internal suspend fun deliverDocToChats(
    initial: DocChatDelivery,
    send: suspend (String) -> Boolean,
    grantAccess: suspend (Set<String>) -> Boolean,
    onProgress: (DocChatDelivery) -> Unit,
): DocChatDelivery {
    var progress = initial
    for (cid in initial.pending) {
        if (attempt { send(cid) }) {
            progress = progress.copy(
                pending = progress.pending - cid,
                delivered = progress.delivered + cid,
                awaitingAccess = progress.awaitingAccess + cid,
            )
            onProgress(progress)
        }
    }
    // Access is only granted for cards confirmed as persisted by IM.
    if (progress.awaitingAccess.isNotEmpty() && attempt { grantAccess(progress.awaitingAccess) }) {
        progress = progress.copy(awaitingAccess = emptySet())
        onProgress(progress)
    }
    return progress
}

private suspend fun attempt(action: suspend () -> Boolean): Boolean {
    currentCoroutineContext().ensureActive()
    val result = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    currentCoroutineContext().ensureActive()
    return result
}
