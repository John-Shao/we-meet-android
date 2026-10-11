package com.we.meet.feature.assistant.aicall.rtc

import com.we.meet.feature.assistant.aicall.model.PhotoCleanupException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Lend the camera to still capture while retaining the same call and mode. */
internal suspend fun <T> withPhotoVideoPause(
    video: Boolean,
    current: () -> Boolean,
    pause: suspend () -> Unit,
    resume: suspend () -> Unit,
    capture: suspend () -> T,
): T {
    if (!video) return capture()
    try { withTimeout(10_000) { pause() } }
    catch (error: Exception) { throw PhotoCleanupException(error) }
    var unsafeCapture = false
    try { return capture() }
    catch (error: PhotoCleanupException) { unsafeCapture = true; throw error }
    finally {
        if (!unsafeCapture) withContext(NonCancellable) {
            if (current()) try { withTimeout(10_000) { resume() } }
            catch (error: Exception) { throw PhotoCleanupException(error) }
        }
    }
}
