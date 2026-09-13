package com.we.meet.ui.records

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.data.api.dto.*
import com.we.meet.data.capture.*
import com.we.meet.data.repository.MeetingInterpretationRepository
import com.we.meet.livekit.InterpretationTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InterpretationController(val session: InterpretationSession) {
    var version by mutableIntStateOf(0)
    var read by mutableStateOf<Result<InterpretationStateDto>?>(null)
    var pendingChannel by mutableStateOf<MeetingIntent?>(null)
    var pendingListen by mutableStateOf<MeetingIntent?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf(false)
    var storageError by mutableStateOf(false)
    var resumed by mutableStateOf(false)
    var writable by mutableStateOf(false)
    var fresh by mutableStateOf(false)
    var seenListening by mutableStateOf(false)
    var restart by mutableIntStateOf(0)
    var beforeListen: () -> Unit = {}
    var control: (InterpretationChannelRequestDto) -> Unit = {}
    var choose: (InterpretationChannelDto?) -> Unit = {}
    var retryChannel: () -> Unit = {}
    var retryListen: () -> Unit = {}
    var sound: (Boolean) -> Unit = {}
    var refresh: () -> Unit = {}
    val pending get() = pendingChannel != null || pendingListen != null
    val enabled get() = resumed && writable && !busy && !storageError
}

@Composable
internal fun rememberInterpretation(viewer: String, room: String, sid: String, repository: MeetingInterpretationRepository,
    currentViewer: () -> String?, transport: InterpretationTransport): InterpretationController {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val getViewer by rememberUpdatedState(currentViewer)
    val state = remember(viewer, room, sid, transport) { InterpretationController(InterpretationSession(sid, transport.localSid)) }
    var coordinator by remember(state) { mutableStateOf<MeetingInterpretationCoordinator?>(null) }
    var action by remember(state) { mutableStateOf<Job?>(null) }
    val readRequests = remember(state) { Channel<Unit>(Channel.CONFLATED) }
    val readLock = remember(state) { Mutex() }
    fun current() = viewer.isNotBlank() && getViewer() == viewer && transport.current()
    fun update() {
        if (state.session.desired != null) state.seenListening = true
        state.fresh = state.session.fresh(SystemClock.elapsedRealtime())
        transport.grants(if (current() && state.resumed) state.session.grants(transport.activeSources(), SystemClock.elapsedRealtime()) else emptyList())
        state.version++
    }
    suspend fun loadPending(controller: MeetingInterpretationCoordinator) {
        state.pendingChannel = controller.pendingChannel(room, sid)
        state.pendingListen = controller.pendingListen(room, sid, transport.localSid)
    }
    suspend fun read(): Boolean = readLock.withLock {
        if (!current() || !state.resumed) return@withLock false
        val started = SystemClock.elapsedRealtime()
        val result = repository.state(viewer, room, sid)
        if (!current() || !state.resumed) return@withLock false
        val wasListening = state.session.desired != null
        state.read = result
        result.fold({ state.session.accept(it, started, SystemClock.elapsedRealtime()) }, { state.session.clear() })
        if (wasListening && state.session.desired == null) state.error = true
        update(); result.isSuccess
    }
    LaunchedEffect(state, lifecycle, state.restart) {
        if (viewer.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var store: MeetingIntentStore? = null
            try {
                state.resumed = true
                try {
                    withContext(Dispatchers.IO) { store = MeetingIntentStore.open(context, viewer) { getViewer() } }
                    coordinator = MeetingInterpretationCoordinator(viewer, requireNotNull(store), repository)
                    loadPending(coordinator!!); state.storageError = false; state.writable = true
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: Exception) { state.storageError = true }
                coroutineScope {
                    launch {
                        while (isActive && current()) {
                            if (read()) withTimeoutOrNull(5000) { readRequests.receive() } else readRequests.receive()
                        }
                    }
                    launch {
                        transport.packets().collect { packet ->
                            if (current() && state.session.receive(packet.payload, packet.identity, packet.participantSid, packet.agent, transport.activeSources(), SystemClock.elapsedRealtime())) update()
                        }
                    }
                    launch {
                        while (isActive) {
                            delay(5000)
                            val wanted = state.session.desired ?: continue
                            val connection = state.session.connection ?: continue
                            if (!current() || !state.session.authorized(SystemClock.elapsedRealtime())) continue
                            val started = SystemClock.elapsedRealtime()
                            val result = repository.renew(viewer, wanted.id, InterpretationRenewRequestDto(room, sid, connection.id, wanted.channelId, wanted.revision))
                            if (!current() || !state.resumed || state.session.desired != wanted) continue
                            val accepted = result.getOrNull()?.let { state.session.renewed(wanted, it, started, SystemClock.elapsedRealtime()) } == true
                            if (!accepted) { state.session.silence(); state.error = true }
                            update()
                        }
                    }
                    launch {
                        while (isActive) {
                            val wanted = state.session.desired
                            if (!current()) { state.session.clear(); state.read = Result.failure(IllegalStateException("Meeting changed")) }
                            state.session.expire(SystemClock.elapsedRealtime())
                            if (wanted != null && state.session.desired == null) state.error = true
                            // Source departures revoke tracks even while status/renewal HTTP is in flight.
                            state.session.pruneSources(transport.activeSources())
                            update(); delay(250)
                        }
                    }
                    awaitCancellation()
                }
            } finally {
                state.resumed = false; state.writable = false; coordinator = null
                state.session.clear(); transport.grants(emptyList())
                withContext(NonCancellable) { action?.cancelAndJoin(); withContext(Dispatchers.IO) { store?.close() } }
                action = null; state.read = null; state.pendingChannel = null; state.pendingListen = null; state.busy = false; state.fresh = false; state.version++
            }
        }
    }
    fun perform(command: suspend (MeetingInterpretationCoordinator) -> Unit) {
        val controller = coordinator ?: return
        if (!state.enabled || !current() || state.read?.isSuccess != true || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        state.busy = true; state.error = false
        action = scope.launch {
            try { command(controller) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { state.error = true; state.session.silence(); update() }
            finally {
                if (coordinator === controller && state.resumed) {
                    try { loadPending(controller) }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { state.storageError = true }
                    state.busy = false; readRequests.trySend(Unit)
                }
            }
        }
    }
    suspend fun listen(controller: MeetingInterpretationCoordinator, request: InterpretationListenRequestDto) {
        val result = controller.subscribe(transport.localSid, request)
        if (read() && current() && state.resumed && request.operation == "join" && !result.replayed) {
            if (state.session.listen(InterpretationSession.selection(result.result), SystemClock.elapsedRealtime())) { state.beforeListen(); update() }
        }
    }
    state.refresh = { if (state.storageError) state.restart++ else readRequests.trySend(Unit) }
    state.control = control@{ request ->
        val observed = state.read?.getOrNull() ?: return@control
        val previous = observed.channels.singleOrNull { it.target == request.target }
        if (state.pending || !observed.canControl || request.roomId != room || request.livekitRoomSid != sid || request.expectedChannelId != previous?.id || request.operation == "start" && (!state.fresh || !observed.available || previous?.state in MeetingInterpretationRepository.activeStates)) return@control
        if (request.operation == "stop" && state.session.desired?.channelId == request.expectedChannelId) { state.session.silence(); state.seenListening = false; update() }
        perform { it.control(request); read() }
    }
    state.choose = choose@{ target ->
        val connection = state.session.connection ?: return@choose
        val previous = state.session.subscription
        val observed = state.read?.getOrNull() ?: return@choose
        if (!state.enabled || state.pending || target != null && (!state.fresh || !observed.available || observed.channels.none { it == target } || target.state !in setOf("prepared", "starting", "translating"))) return@choose
        state.session.silence(); update()
        if (target == null) state.seenListening = false
        val channelId = target?.id ?: previous?.channelId ?: return@choose
        val request = InterpretationListenRequestDto(room, sid, if (target == null) "leave" else "join", connection.id, channelId, previous?.revision ?: 0)
        perform { listen(it, request) }
    }
    state.retryChannel = {
        val original = state.pendingChannel?.let { runCatching { MeetingInterpretationRepository.channelAdapter.fromJson(it.body) }.getOrNull() }
        if (original != null && state.read?.getOrNull()?.canControl == true) perform { it.control(original); read() }
    }
    state.retryListen = {
        val original = state.pendingListen?.let { runCatching { MeetingInterpretationRepository.listenIntentAdapter.fromJson(it.body) }.getOrNull() }
        if (original != null && original.localSid == transport.localSid) perform { listen(it, original.request) }
    }
    state.sound = { enabled ->
        if (current() && state.resumed && (!enabled || !state.busy && !state.pending && state.session.authorized(SystemClock.elapsedRealtime()))) {
            state.session.sound(enabled, SystemClock.elapsedRealtime()); if (enabled) state.beforeListen(); update()
        }
    }
    return state
}
