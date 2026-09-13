package com.we.meet.ui.records

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingTranslationRepository
import com.we.meet.livekit.PrivateTranslationTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PrivateTranslationController(val session: PrivateTranslationSession) {
    var version by mutableIntStateOf(0)
    var read by mutableStateOf<Result<PrivateTranslationStateDto>?>(null)
    var pending by mutableStateOf<MeetingIntent?>(null)
    var busy by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var error by mutableStateOf(false)
    var storageError by mutableStateOf(false)
    var resumed by mutableStateOf(false)
    var writable by mutableStateOf(false)
    var fresh by mutableStateOf(false)
    var seenRun by mutableStateOf(false)
    var beforeListen: () -> Unit = {}
    var restart by mutableIntStateOf(0)
    var submit: (PrivateTranslationRequestDto) -> Unit = {}
    var sound: (Boolean) -> Unit = {}
    var turn: (String, Boolean, Boolean) -> Unit = { _, _, _ -> }
    var refresh: () -> Unit = {}
    val enabled get() = resumed && writable && !busy && !storageError
}

@Composable
internal fun rememberPrivateTranslation(viewer: String, room: String, sid: String, repository: MeetingTranslationRepository,
    currentViewer: () -> String?, transport: PrivateTranslationTransport): PrivateTranslationController {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val getViewer by rememberUpdatedState(currentViewer)
    val state = remember(viewer, room, sid, transport) { PrivateTranslationController(PrivateTranslationSession(sid, transport.localSid)) }
    var coordinator by remember(state) { mutableStateOf<MeetingTranslationCoordinator?>(null) }
    var action by remember(state) { mutableStateOf<Job?>(null) }
    var speech by remember(state) { mutableStateOf<Job?>(null) }
    val readRequests = remember(state) { Channel<Unit>(Channel.CONFLATED) }
    val sendLock = remember(state) { Mutex() }
    fun current() = viewer.isNotBlank() && getViewer() == viewer && transport.current()
    fun update() {
        state.fresh = state.session.fresh(SystemClock.elapsedRealtime())
        transport.grants(if (current() && state.resumed) listOfNotNull(state.session.grant(SystemClock.elapsedRealtime())) else emptyList())
        state.version++
    }
    suspend fun send(command: PrivateTranslationCommand) = sendLock.withLock {
        check(current())
        withTimeout(2000) { transport.send(command) }
    }
    LaunchedEffect(state, lifecycle, state.restart) {
        if (viewer.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                state.resumed = true
                try {
                    withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer) { getViewer() } }
                    coordinator = MeetingTranslationCoordinator(viewer, requireNotNull(store), repository)
                    state.pending = coordinator!!.pending(room, sid); state.storageError = false; state.writable = true
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { state.storageError = true }
                coroutineScope {
                    launch {
                        while (isActive && current()) {
                            val started = SystemClock.elapsedRealtime()
                            val result = repository.state(viewer, room, sid)
                            if (!current()) break
                            state.read = result
                            result.fold({ state.session.accept(it, started, SystemClock.elapsedRealtime()); state.seenRun = it.current?.state in MeetingTranslationRepository.activeStates }, { state.session.failedRead() })
                            update()
                            if (result.isFailure) readRequests.receive()
                            else withTimeoutOrNull(5000) { readRequests.receive() }
                        }
                    }
                    launch {
                        transport.packets().collect { packet ->
                            val wasReady = state.session.ready
                            if (current() && state.session.receive(packet.payload, packet.identity, packet.participantSid, packet.agent, SystemClock.elapsedRealtime())) {
                                // A reconnect/process recreation must finish an abandoned manual turn,
                                // never silently continue capturing it after microphone reconnection.
                                if (!wasReady) state.session.held?.let { direction ->
                                    state.session.turn(direction, false, false, SystemClock.elapsedRealtime())?.let { command ->
                                        try { send(command) }
                                        catch (canceled: CancellationException) { state.session.failedCommand(); if (canceled !is TimeoutCancellationException) throw canceled }
                                        catch (_: Exception) { state.session.failedCommand() }
                                    }
                                }
                                update()
                            }
                        }
                    }
                    launch {
                        while (isActive) {
                            if (current()) transport.agents().mapNotNull { state.session.sync(it, SystemClock.elapsedRealtime()) }.take(4).forEach { command ->
                                try { send(command) }
                                catch (canceled: CancellationException) { if (canceled !is TimeoutCancellationException) throw canceled }
                                catch (_: Exception) { /* The short API lease still bounds all playback. */ }
                            }
                            delay(3000)
                        }
                    }
                    launch {
                        while (isActive) {
                            if (!current()) { state.session.clear(); state.read = Result.failure(IllegalStateException("Meeting changed")) }
                            val fresh = state.session.fresh(SystemClock.elapsedRealtime())
                            if (fresh != state.fresh || !current()) update()
                            delay(250)
                        }
                    }
                    awaitCancellation()
                }
            } finally {
                state.resumed = false; state.writable = false; coordinator = null
                state.session.silence(); transport.grants(emptyList())
                withContext(NonCancellable) {
                    // Give an already-issued begin/end a bounded chance to finish, then release a held turn.
                    withTimeoutOrNull(1000) { speech?.join() }; speech?.cancelAndJoin()
                    if (current()) state.session.held?.let { direction ->
                        state.session.turn(direction, false, false, SystemClock.elapsedRealtime())?.let { command ->
                            try { withTimeout(1000) { send(command) } } catch (_: Exception) { state.session.failedCommand() }
                        }
                    }
                    action?.cancelAndJoin()
                    withContext(Dispatchers.IO) { store?.close() }
                }
                state.session.clear(); state.read = null; state.pending = null; state.busy = false; state.sending = false; state.fresh = false; state.version++
                action = null; speech = null
            }
        }
    }
    state.refresh = { if (state.storageError) state.restart++ else readRequests.trySend(Unit) }
    state.submit = submit@{ request ->
        val controller = coordinator ?: return@submit
        val observed = state.read?.getOrNull() ?: return@submit
        if (!state.enabled || !current() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@submit
        if (request.roomId != room || request.livekitRoomSid != sid || state.pending == null && request.operation == "start" && !state.fresh) return@submit
        if (state.pending == null && (request.expectedRunId != observed.current?.id || request.operation == "start" && (!observed.available || observed.current?.state in MeetingTranslationRepository.activeStates || observed.sources.none { it.id == request.sourceParticipationId && it.participantSid == transport.localSid }))) return@submit
        state.busy = true; state.error = false
        if (request.operation == "stop") { state.session.silence(); update() }
        action = scope.launch {
            try { controller.control(request) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state.error = true }
            finally {
                if (coordinator === controller && state.resumed) {
                    try { state.pending = controller.pending(room, sid) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { state.storageError = true }
                    state.busy = false; readRequests.trySend(Unit)
                }
            }
        }
    }
    state.sound = { enabled -> if (current() && state.resumed && (!enabled || !state.busy && state.pending == null)) {
        if (state.session.setSound(enabled, SystemClock.elapsedRealtime()) && enabled) state.beforeListen()
        update()
    } }
    state.turn = turn@{ direction, begin, microphone ->
        if (!current() || !state.resumed || state.sending || state.busy || state.pending != null) return@turn
        val command = state.session.turn(direction, begin, microphone, SystemClock.elapsedRealtime()) ?: return@turn
        state.sending = true; update()
        speech = scope.launch {
            try { send(command) }
            catch (canceled: CancellationException) {
                state.session.failedCommand()
                if (canceled !is TimeoutCancellationException) throw canceled
            }
            catch (_: Exception) { state.session.failedCommand() }
            finally { state.sending = false; update() }
        }
    }
    return state
}
