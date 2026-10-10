package com.we.meet.ui.voiceprint

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.we.meet.WeMeetApp
import com.we.meet.data.voiceprint.VoiceprintInputChanges
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CallOccurrence(val room: String, val participant: String, val identity: String, val epoch: Long)
private fun occurrence(room: Room, epoch: Long): CallOccurrence? {
    if (room.state != Room.State.CONNECTED) return null
    val sid = room.sid?.sid ?: return null
    val participant = room.localParticipant.sid.value
    if (sid.isEmpty() || participant.isEmpty()) return null
    return CallOccurrence(sid, participant, room.localParticipant.identity?.value.orEmpty(), epoch)
}

/** All three RoomScreen variants share one metadata-only, login-bound sampling host. */
@Composable
internal fun VoiceprintCallRoomHost(app: WeMeetApp, room: Room, routes: StateFlow<Long>,
    microphoneEnabled: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    var epoch by remember(room) { mutableLongStateOf(0) }
    var login by remember(app) { mutableStateOf(app.tokenStore.authSnapshot().session) }
    var owner by remember(app) { mutableStateOf(app.captureAccount) }
    var connected by remember(room) { mutableStateOf(occurrence(room, epoch)) }
    var track by remember(room) { mutableStateOf(room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track) }
    var trackRevision by remember(room) { mutableLongStateOf(0) }
    LaunchedEffect(room, app) {
        launch {
            room.events.collect { event ->
                if (event is RoomEvent.Reconnecting || event is RoomEvent.Disconnected) { epoch++; connected = null }
                if (event is RoomEvent.Reconnected) { epoch++; connected = occurrence(room, epoch) }
            }
        }
        while (true) {
            val nextLogin = app.tokenStore.authSnapshot().session
            if (login != nextLogin) { epoch++; login = nextLogin }
            owner = app.captureAccount
            connected = occurrence(room, epoch)
            val nextTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track
            if (nextTrack != null && track != null && nextTrack !== track) trackRevision++
            if (nextTrack != null) track = nextTrack
            delay(250)
        }
    }
    val current = connected ?: return
    val viewer = owner ?: return
    key(viewer, login, current) {
        val client = remember { runCatching { app.voiceprintRepository.openCall(viewer, current.room, current.participant) }.getOrNull() }
        if (client != null) {
            val scope = rememberCoroutineScope()
            val controller = remember { VoiceprintCallController(SessionVoiceprintCallOperations(client), scope) }
            var settings by remember { mutableStateOf<Pair<String?, String?>?>(null) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(controller) { onDispose { controller.close() } }
            LaunchedEffect(controller, lifecycle) {
                if (current.epoch > 0) controller.invalidateDevice()
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    controller.start()
                    try {
                        launch { while (true) { delay(3000); controller.refresh() } }
                        while (controller.allowed()) { controller.tick(); delay(250) }
                    } finally { settings = null; withContext(NonCancellable) { controller.background() } }
                }
            }
            var previousTrack by remember(controller) { mutableLongStateOf(trackRevision) }
            val route by routes.collectAsState()
            var previousRoute by remember(controller) { mutableLongStateOf(route) }
            LaunchedEffect(trackRevision, route, controller) {
                if (previousTrack != trackRevision || previousRoute != route) controller.invalidateDevice()
                previousTrack = trackRevision; previousRoute = route
            }
            val state by controller.state.collectAsState()
            if (state.enabled) {
                val context = LocalContext.current
                val inputs = remember(controller) { runCatching { VoiceprintInputChanges(context.applicationContext) }.getOrNull() }
                DisposableEffect(inputs) { onDispose { inputs?.close() } }
                LaunchedEffect(inputs, controller) {
                    controller.inputObservationAvailable(inputs != null)
                    var previous = inputs?.revision?.value
                    inputs?.revision?.collect { value -> if (previous != value) controller.invalidateDevice(); previous = value }
                }
            }
            VoiceprintCallWidget(controller, microphoneEnabled, compact, onSettings = { id, name -> settings = id to name }, modifier = modifier)
            val selected = settings
            val dialogContext = LocalContext.current
            val dialogConfiguration = LocalConfiguration.current
            val dialogDensity = LocalDensity.current
            if (selected != null && controller.allowed() && !compact) {
                Dialog(onDismissRequest = { settings = null; controller.refresh() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    CompositionLocalProvider(LocalContext provides dialogContext, LocalConfiguration provides dialogConfiguration, LocalDensity provides dialogDensity) {
                        VoiceprintSettingsScreen(app.voiceprintRepository, viewer,
                            onBack = { settings = null; controller.refresh() }, initialOrganizationId = selected.first, initialOrganizationName = selected.second)
                    }
                }
            }
        }
    }
}
