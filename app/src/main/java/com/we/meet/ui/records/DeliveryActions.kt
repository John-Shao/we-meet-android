package com.we.meet.ui.records

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingDeliveryRepository
import kotlinx.coroutines.*

/** Shared lifecycle for explicit delivery actions. Opening a page only loads encrypted metadata. */
internal class DeliveryActions {
    var pending by mutableStateOf<Map<MeetingIntentKind, MeetingIntent>>(emptyMap())
    var busy by mutableStateOf(false)
    var error by mutableStateOf(false)
    var storageError by mutableStateOf(false)
    var storageRetry by mutableIntStateOf(0)
    var refresh by mutableIntStateOf(0)
    var ready by mutableStateOf(false)
    var perform: ((suspend (MeetingDeliveryCoordinator) -> Unit) -> Unit) = {}
    val enabled get() = ready && !busy && !storageError
}

@Composable
internal fun rememberDeliveryActions(viewer: String, record: String, repository: MeetingDeliveryRepository, currentViewer: () -> String?): DeliveryActions {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state = remember(viewer, record) { DeliveryActions() }
    var coordinator by remember(viewer, record) { mutableStateOf<MeetingDeliveryCoordinator?>(null) }
    var action by remember(viewer, record) { mutableStateOf<Job?>(null) }
    suspend fun load(controller: MeetingDeliveryCoordinator) = buildMap {
        MeetingDeliveryCoordinator.kinds.forEach { kind -> controller.pending(kind, record)?.let { put(kind, it) } }
    }
    LaunchedEffect(viewer, record, lifecycle, state.storageRetry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer, currentViewer) }
                val controller = MeetingDeliveryCoordinator(viewer, requireNotNull(store), repository)
                state.pending = load(controller); state.storageError = false; coordinator = controller; state.ready = true
                awaitCancellation()
            } catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state.storageError = true }
            finally {
                coordinator = null; state.ready = false
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; state.busy = false; state.pending = emptyMap()
            }
        }
    }
    state.perform = perform@{ command ->
        val controller = coordinator ?: return@perform
        if (!state.enabled || currentViewer() != viewer || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@perform
        state.busy = true; state.error = false
        action = scope.launch {
            try { command(controller) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state.error = true }
            finally {
                if (coordinator === controller && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    try { state.pending = load(controller) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { state.storageError = true }
                    state.busy = false; state.refresh++
                }
            }
        }
    }
    return state
}
