package com.we.meet.ui.room

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.LocalIsInPipMode
import com.we.meet.MainActivity
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.audio.AudioOutput
import com.we.meet.audio.AudioOutputController
import com.we.meet.audio.AudioOutputStore
import androidx.compose.runtime.saveable.rememberSaveable
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.call.MeetInviteTracker
import com.we.meet.feature.im.ui.call.CallGridParticipant
import com.we.meet.feature.im.ui.call.MinimalVideoCallScreen
import com.we.meet.feature.im.ui.call.MinimalVoiceCallScreen
import com.we.meet.overlay.ScreenShareOverlay
import io.livekit.android.compose.ui.ScaleType
import io.livekit.android.compose.ui.VideoTrackView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RoomToolbarIconButtonSize = 40.dp
private val RoomToolbarIconSize = 24.dp
// Bottom toolbar: container matches the icon exactly so the only gap
// between icon and label is BottomToolbarLabelSpacing. A larger container
// (e.g. the top toolbar's 40dp) adds invisible padding that pushes the
// label too far below.
private val BottomToolbarIconButtonSize = 24.dp
private val BottomToolbarLabelSpacing = 2.dp

@Composable
fun RoomScreen(
    roomId: String,
    livekitUrl: String,
    livekitToken: String,
    roomName: String,
    roomSlug: String,
    host: String?,
    createdAtMs: Long,
    isAdmin: Boolean,
    initialMicEnabled: Boolean = true,
    initialCameraEnabled: Boolean = true,
    // Set when the room was entered from a 1:1 call. callMedia == "audio" (with
    // a non-null peer) swaps the meeting grid for the minimal voice-call UI.
    callPeerUid: String? = null,
    callPeerName: String? = null,
    callMedia: String? = null,
    // P4: entered as an accepted escalation invite — start in the multi-party
    // form (voice grid / full meeting UI), never the 1:1 stage.
    callMeet: Boolean = false,
    onLeave: (hostEnded: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application

    val viewModel: RoomViewModel = viewModel(
        factory = RoomViewModel.Factory(
            app, roomId, livekitUrl, livekitToken, roomName, roomSlug,
            host, createdAtMs,
            initialMicEnabled, initialCameraEnabled, isAdmin,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subtitleSegments by viewModel.subtitleSegments.collectAsStateWithLifecycle()
    val aiMessages by viewModel.aiMessages.collectAsStateWithLifecycle()
    val aiAsking by viewModel.aiAsking.collectAsStateWithLifecycle()

    // Host-ended-meeting auto-leave. When the server tells us the room was
    // deleted (not a local leave), pop back to Home and let AppNav show the
    // "host ended" sheet.
    LaunchedEffect(state.hostEnded) {
        if (state.hostEnded) onLeave(true)
    }

    // Restart the local camera when the Activity returns from background /
    // screen-lock. Android releases the camera on lock, and LiveKit doesn't
    // auto-restart it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onLifecycleStop()
                Lifecycle.Event.ON_START -> viewModel.onLifecycleStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Drive MainActivity's "allow auto-enter PiP" gate. We only want the
    // home-gesture to collapse to a PiP window when the user is actually
    // watching a live meeting — a Connecting spinner or Error view would
    // PiP a blank tile otherwise.
    val activity = context as? MainActivity
    DisposableEffect(activity, state.phase) {
        activity?.setMeetingInProgress(state.phase == RoomUiState.Phase.Connected)
        onDispose { activity?.setMeetingInProgress(false) }
    }

    val isInPip = LocalIsInPipMode.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isInPip && state.phase == RoomUiState.Phase.Connected) {
            PipLayout(room = viewModel.room, state = state)
        } else {
            when (state.phase) {
                RoomUiState.Phase.Connecting -> ConnectingView()
                RoomUiState.Phase.Error -> ErrorView(state.errorMessage) { onLeave(false) }
                RoomUiState.Phase.Connected,
                RoomUiState.Phase.Disconnected -> {
                    // 1:1 call → Feishu/WeChat-style minimal in-call UI instead
                    // of the meeting grid (voice and video variants). Same
                    // viewModel/room underneath, so media, call-log duration
                    // and hangup semantics are shared.
                    //
                    // P4 fork(2026-07-17 现状模型拍板):
                    //   voice = CALL semantics throughout — always the minimal
                    //     stage; its form follows the live roster inside
                    //     (grid ↔ 1:1 ↔ auto-end when alone).
                    //   video = escalation LATCHES into the full meeting UI
                    //     (meeting semantics — no fallback at 2, no auto-end).
                    val imSession = remember {
                        ImSession.get(context.applicationContext as ImDeps)
                    }
                    val isCallEntry = callPeerUid != null || callMeet
                    val invitesSent by imSession.meetInvites.upgraded
                        .collectAsStateWithLifecycle()
                    val invites by imSession.meetInvites.invites
                        .collectAsStateWithLifecycle()
                    var upgradeLatch by rememberSaveable { mutableStateOf(callMeet) }
                    val remoteCount = state.participants.count { !it.isLocal && !it.isScreenShare }

                    // M2: broadcast the LOCAL active-invite snapshot on change
                    // (empty json clears peers' chips). Lives above the form
                    // fork so it keeps firing after video switches to the
                    // full meeting UI while invitees still ring.
                    val activeLocal = invites.filter { !it.terminal }
                    val activeKey = activeLocal.joinToString(",") { "${it.callId}:${it.state}" }
                    var publishedKey by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(activeKey) {
                        if (!isCallEntry) return@LaunchedEffect
                        if (publishedKey == activeKey) return@LaunchedEffect
                        if (publishedKey == null && activeKey.isEmpty()) return@LaunchedEffect
                        publishedKey = activeKey
                        val json = org.json.JSONObject().apply {
                            put(
                                "invites",
                                org.json.JSONArray().apply {
                                    activeLocal.forEach { inv ->
                                        put(
                                            org.json.JSONObject().apply {
                                                put("label", inv.label)
                                                inv.avatarUrl?.let { put("avatarUrl", it) }
                                                put(
                                                    "state",
                                                    if (inv.state == MeetInviteTracker.InviteState.RINGING) {
                                                        "ringing"
                                                    } else {
                                                        "inviting"
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        }.toString()
                        viewModel.publishMeetInvites(json)
                    }

                    // M2: co-participants' ringing invites — a sender leaving
                    // the room invalidates their snapshot (invites were
                    // canceled on leave).
                    val remoteInvitesMap by viewModel.remoteMeetInvites
                        .collectAsStateWithLifecycle()
                    val presentIds = state.participants
                        .filter { !it.isLocal }.map { it.identity }.toSet()
                    val remoteChips = remoteInvitesMap
                        .filterKeys { it in presentIds }.values.flatten()

                    LaunchedEffect(invitesSent, remoteCount, remoteChips.size) {
                        if (invitesSent || remoteCount >= 2 ||
                            (isCallEntry && remoteChips.isNotEmpty())
                        ) {
                            upgradeLatch = true
                        }
                    }

                    // M2: owner-side rename once the call truly became
                    // multi-party (meeting history stops showing「与X的通话」).
                    var renamed by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(remoteCount) {
                        if (renamed || !isCallEntry || !isAdmin || remoteCount < 2) {
                            return@LaunchedEffect
                        }
                        renamed = true
                        val selfName = state.participants
                            .firstOrNull { it.isLocal }?.name.orEmpty()
                        viewModel.renameRoom(
                            context.getString(
                                com.we.meet.feature.im.R.string.im_meet_invite_room_name,
                                selfName,
                            ),
                        )
                    }

                    val callIsVideo = callMedia == "video"
                    if (isCallEntry && (callMedia == "audio" || (callIsVideo && !upgradeLatch))) {
                        MinimalCallHost(
                            state = state,
                            viewModel = viewModel,
                            peerUid = callPeerUid.orEmpty(),
                            peerName = callPeerName ?: callPeerUid.orEmpty(),
                            isVideo = callIsVideo,
                            roomSlug = roomSlug,
                            imSession = imSession,
                            remoteInvites = remoteChips,
                            onLeave = {
                                viewModel.leave()
                                onLeave(false)
                            },
                        )
                    } else {
                        RoomContent(
                        state = state,
                        room = viewModel.room,
                        pinPreferredAudioDevice = viewModel.callAudioDeviceModule::setPreferredDevice,
                        roomName = roomName,
                        roomSlug = roomSlug,
                        isAdmin = isAdmin,
                        onToggleMic = viewModel::toggleMic,
                        onToggleCamera = viewModel::toggleCamera,
                        onSwitchCamera = viewModel::switchCamera,
                        onPinParticipant = viewModel::pinParticipant,
                        onUnpinParticipant = viewModel::unpinParticipant,
                        onSendMessage = viewModel::sendChatMessage,
                        onStartScreenShare = viewModel::startScreenShare,
                        onStopScreenShare = viewModel::stopScreenShare,
                        onRenameSelf = viewModel::renameSelf,
                        onToggleHand = viewModel::toggleHand,
                        onMuteParticipant = viewModel::muteParticipantMicrophone,
                        onRemoveParticipant = viewModel::removeParticipant,
                        onAdmitParticipant = viewModel::admitParticipant,
                        onRefreshAccessLevel = viewModel::refreshAccessLevel,
                        onUpdateAccessLevel = viewModel::updateAccessLevel,
                        onToggleRecording = viewModel::toggleRecording,
                        onToggleSubtitles = viewModel::toggleSubtitles,
                        subtitleSegments = subtitleSegments,
                        aiMessages = aiMessages,
                        aiAsking = aiAsking,
                        onAskAi = viewModel::askAi,
                        onClearAi = viewModel::clearAi,
                        onLeave = {
                            viewModel.leave()
                            onLeave(false)
                        },
                        onEndMeeting = {
                            viewModel.endMeeting { onLeave(false) }
                        },
                    )
                    }
                }
            }
        }
    }
}

/**
 * Wires the minimal 1:1 call UI (feature-im, voice or video variant) to this
 * room: mic/camera state from the RoomViewModel, audio routing via
 * [AudioOutputController] — voice starts on EARPIECE (a voice call is a phone
 * call), video on SPEAKER (the phone is held at arm's length) — video
 * surfaces injected as [io.livekit.android.compose.ui.VideoTrackView] slots,
 * and hangup via the caller-provided leave.
 */
@Composable
private fun MinimalCallHost(
    state: RoomUiState,
    viewModel: RoomViewModel,
    peerUid: String,
    peerName: String,
    isVideo: Boolean,
    roomSlug: String,
    imSession: ImSession,
    remoteInvites: List<Triple<String, String, String?>> = emptyList(),
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val audioOutputController = remember(context) {
        AudioOutputController(
            context = context,
            muteOutput = viewModel.room::setSpeakerMute,
            pinPreferredDevice = viewModel.callAudioDeviceModule::setPreferredDevice,
        )
    }
    var audioOutput by remember {
        // Voice starts on EARPIECE (a call is a phone call), video on SPEAKER
        // (the phone is held at arm's length).
        mutableStateOf(if (isVideo) AudioOutput.Speaker else AudioOutput.Earpiece)
    }
    DisposableEffect(audioOutputController) {
        audioOutputController.start()
        onDispose { audioOutputController.stop() }
    }
    LaunchedEffect(audioOutput) {
        audioOutputController.apply(audioOutput)
    }
    // Call semantics: everyone else leaving ends the call on this side too —
    // 1:1 AND multi-party voice alike (现状1/问题5 拍板: a call is a call;
    // only meetings outlive their participants). Armed only after a peer has
    // actually been seen, debounced against LiveKit-reconnect roster blips,
    // and HELD while an escalation invite is still ringing (auto-ending
    // would cancel the invitee mid-ring).
    val invites by imSession.meetInvites.invites.collectAsStateWithLifecycle()
    val pendingInvites = invites.count { !it.terminal }
    var peerSeen by remember { mutableStateOf(false) }
    LaunchedEffect(state.participants, state.phase, pendingInvites) {
        if (pendingInvites > 0) return@LaunchedEffect
        val remoteCount = state.participants.count { !it.isLocal }
        if (remoteCount > 0) {
            peerSeen = true
            return@LaunchedEffect
        }
        if (peerSeen && state.phase == RoomUiState.Phase.Connected) {
            delay(1_500)
            onLeave()
        }
    }
    val deps = context.applicationContext as ImDeps
    val connected = state.phase == RoomUiState.Phase.Connected
    val onToggleSpeaker = {
        audioOutput = if (audioOutput == AudioOutput.Speaker) {
            AudioOutput.Earpiece
        } else {
            AudioOutput.Speaker
        }
    }
    // P4 拉人 picker: multi-select org members → parallel meet-invites over
    // the tracker (kind="meet", current room slug — no new room).
    var showEscalatePicker by rememberSaveable { mutableStateOf(false) }
    if (showEscalatePicker) {
        ContactPicker(
            deps = context.applicationContext as DirectoryDeps,
            mode = ContactPickerMode.Multi,
            onConfirm = { picked ->
                showEscalatePicker = false
                if (picked.isNotEmpty()) {
                    val selfName = state.participants
                        .firstOrNull { it.isLocal }?.name.orEmpty()
                    imSession.meetInvites.sendInvites(
                        targets = picked.map {
                            MeetInviteTracker.Target(it.userId, it.displayName, it.avatarUrl)
                        },
                        media = if (isVideo) "video" else "audio",
                        roomSlug = roomSlug,
                        roomName = context.getString(
                            com.we.meet.feature.im.R.string.im_meet_invite_room_name,
                            selfName,
                        ),
                    )
                }
            },
            onDismiss = { showEscalatePicker = false },
        )
    }
    val onAddMember = { showEscalatePicker = true }
    if (isVideo) {
        // Camera tiles only (a 1:1 call has no screen share). Slots are null
        // while the corresponding camera track is absent, letting the screen
        // fall back to avatar / hide the self-view.
        val remote = state.participants.firstOrNull { !it.isLocal && !it.isScreenShare }
        val local = state.participants.firstOrNull { it.isLocal && !it.isScreenShare }
        val remoteTrack = remote?.videoTrack
        val localTrack = local?.videoTrack
        MinimalVideoCallScreen(
            deps = deps,
            peerUid = peerUid,
            fallbackName = peerName,
            connected = connected,
            micEnabled = state.micEnabled,
            onToggleMic = viewModel::toggleMic,
            speakerOn = audioOutput == AudioOutput.Speaker,
            onToggleSpeaker = onToggleSpeaker,
            cameraEnabled = state.cameraEnabled,
            onToggleCamera = viewModel::toggleCamera,
            onFlipCamera = viewModel::switchCamera,
            onHangup = onLeave,
            remoteVideo = remoteTrack?.let { track ->
                {
                    // Re-key across reconnects, same reason as the meeting
                    // grid: a kept SurfaceView stays bound to the dead track.
                    key(state.sessionGeneration, remote.identity) {
                        VideoTrackView(
                            videoTrack = track,
                            modifier = Modifier.fillMaxSize(),
                            passedRoom = viewModel.room,
                            scaleType = ScaleType.Fill,
                        )
                    }
                }
            },
            localVideo = localTrack?.let { track ->
                {
                    key(state.sessionGeneration, local.identity) {
                        VideoTrackView(
                            videoTrack = track,
                            modifier = Modifier.fillMaxSize(),
                            passedRoom = viewModel.room,
                            mirror = true,
                            scaleType = ScaleType.Fill,
                        )
                    }
                }
            },
            onAddMember = onAddMember,
        )
    } else {
        MinimalVoiceCallScreen(
            deps = deps,
            peerUid = peerUid,
            fallbackName = peerName,
            connected = connected,
            micEnabled = state.micEnabled,
            onToggleMic = viewModel::toggleMic,
            speakerOn = audioOutput == AudioOutput.Speaker,
            onToggleSpeaker = onToggleSpeaker,
            onHangup = onLeave,
            gridParticipants = state.participants
                .filter { !it.isScreenShare }
                .map {
                    CallGridParticipant(it.identity, it.name, it.isLocal, it.isSpeaking)
                },
            remoteInvites = remoteInvites,
            onAddMember = onAddMember,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomContent(
    state: RoomUiState,
    room: io.livekit.android.room.Room,
    pinPreferredAudioDevice: (android.media.AudioDeviceInfo?) -> Boolean,
    roomName: String,
    roomSlug: String,
    isAdmin: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onPinParticipant: (String) -> Unit,
    onUnpinParticipant: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStartScreenShare: suspend (Intent) -> Boolean,
    onStopScreenShare: () -> Unit,
    onRenameSelf: suspend (String) -> Result<Unit>,
    onToggleHand: suspend () -> Result<Unit>,
    onMuteParticipant: suspend (String) -> Result<Unit>,
    onRemoveParticipant: suspend (String) -> Result<Unit>,
    onAdmitParticipant: suspend (participantId: String, allow: Boolean) -> Result<Unit>,
    onRefreshAccessLevel: suspend () -> Unit,
    onUpdateAccessLevel: suspend (String) -> Result<Unit>,
    onToggleRecording: () -> Unit,
    onToggleSubtitles: () -> Unit,
    subtitleSegments: List<SubtitleSegment>,
    aiMessages: List<RoomAiMessage>,
    aiAsking: Boolean,
    onAskAi: (String) -> Unit,
    onClearAi: () -> Unit,
    onLeave: () -> Unit,
    onEndMeeting: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioOutputController = remember(context, room) {
        AudioOutputController(
            context = context,
            muteOutput = room::setSpeakerMute,
            pinPreferredDevice = { device -> pinPreferredAudioDevice(device) },
        )
    }
    var toolbarsVisible by remember { mutableStateOf(true) }
    var showParticipants by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showMessages by remember { mutableStateOf(false) }
    var showShareChooser by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showWaitingList by remember { mutableStateOf(false) }
    var showHostSettings by remember { mutableStateOf(false) }
    var showAiSheet by remember { mutableStateOf(false) }
    // One-shot guard so we prompt for SYSTEM_ALERT_WINDOW at most once per
    // meeting instance. If the user declines or ignores, subsequent "共享
    // 屏幕" taps just proceed — sharing still works without the desktop
    // bubble (they can stop from the notification or re-open the app).
    var overlayPermissionPrompted by remember { mutableStateOf(false) }
    // Carry the user's last choice across Preview → Room handoff.
    var audioOutput by remember { mutableStateOf(AudioOutputStore.lastChoice) }

    // MediaProjection consent launcher. The system picker — especially on
    // Android 14+ — lets the user choose "Entire screen" or "A single app"
    // right in the consent dialog. We don't need our own app picker. On
    // older platforms the dialog only offers whole-screen capture, which
    // still matches Tencent Meeting's behaviour there.
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val mainActivity = context as? MainActivity
            scope.launch {
                // Suppress auto-PiP *before* backgrounding: if LiveKit's FGS
                // comes up a moment later while a PiP window has already spawned,
                // MediaProjection captures the PiP meeting UI recursively.
                mainActivity?.setScreenSharing(true)
                val ok = onStartScreenShare(data)
                if (ok) {
                    // Entire-screen vs single-app is decided inside the system
                    // consent dialog; the MediaProjection result Intent is
                    // opaque, so we can't read the mode directly. Instead,
                    // wait a beat and check whether the OS has moved us off
                    // foreground:
                    //   - Still RESUMED  → "Entire screen" (system did nothing)
                    //                      → push the user to the launcher so
                    //                        the share actually shows the
                    //                        desktop, not our meeting UI.
                    //   - Below RESUMED → "Single app" (system already brought
                    //                      the picked app to front) → don't
                    //                      interfere; leave the user on that
                    //                      app.
                    // 400ms is a comfortable margin: long enough to outlast
                    // the task-switch animation Android plays for single-app
                    // mode, short enough that entire-screen mode doesn't feel
                    // laggy before kicking to home.
                    delay(400)
                    val stillForeground = mainActivity?.lifecycle
                        ?.currentState
                        ?.isAtLeast(Lifecycle.State.RESUMED) == true
                    if (stillForeground) {
                        val home = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(home) }
                    }
                } else {
                    mainActivity?.setScreenSharing(false)
                }
            }
        }
    }

    // Re-enable auto-PiP as soon as the user stops sharing (tile button,
    // More sheet, system notification, LiveKit onStop callback — all funnel
    // through state.localScreenSharing). The onSwitch-to-share side above
    // sets the flag proactively; this effect owns the switch-off direction.
    // Same switch also feeds the desktop floating-stop bubble.
    val mainActivity = context as? MainActivity
    LaunchedEffect(state.localScreenSharing) {
        if (!state.localScreenSharing) mainActivity?.setScreenSharing(false)
        ScreenShareOverlay.setSharing(state.localScreenSharing)
    }

    // Tap on the desktop bubble → funnel into the same stop path as every
    // other entry point. Keeping the collection here (rather than inside
    // RoomViewModel) means RoomViewModel stays agnostic of the overlay.
    LaunchedEffect(Unit) {
        ScreenShareOverlay.stopRequests.collect { onStopScreenShare() }
    }

    val requestScreenCapture: () -> Unit = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        if (mpm != null) {
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    DisposableEffect(audioOutputController) {
        audioOutputController.start()
        onDispose { audioOutputController.stop() }
    }
    LaunchedEffect(audioOutput) {
        AudioOutputStore.lastChoice = audioOutput
        audioOutputController.apply(audioOutput)
    }

    // When toolbars are visible, inset the video area so tiles don't sit
    // behind them. Inset = system inset (status/nav bar) + toolbar content
    // height + a fixed visual gap, so the gap stays constant regardless of
    // gesture vs. 3-button navigation. When hidden, video goes fullscreen.
    val insets = WindowInsets
    val statusTop = insets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = insets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Top toolbar content: vertical padding 8*2 + IconButton 40 = 56dp
    // Bottom toolbar content: row pad 4*2 + col pad 4*2 + icon box 24 + spacer 2 + label ~16 = ~58dp
    val gap = 8.dp
    val topInset by animateDpAsState(
        targetValue = if (toolbarsVisible) statusTop + 56.dp + gap else 0.dp,
        label = "topInset",
    )
    val bottomInset by animateDpAsState(
        targetValue = if (toolbarsVisible) navBottom + 58.dp + gap else 0.dp,
        label = "bottomInset",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { toolbarsVisible = !toolbarsVisible },
    ) {
        // Video grid
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset, bottom = bottomInset),
        ) {
            VideoGrid(
                state = state,
                room = room,
                showPinButtons = toolbarsVisible,
                onPin = onPinParticipant,
                onUnpin = onUnpinParticipant,
                onStopScreenShare = onStopScreenShare,
            )
        }

        // Top toolbar (drawer-style)
        AnimatedVisibility(
            visible = toolbarsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopToolbar(
                    roomName = roomName,
                    roomSlug = roomSlug,
                    onMinimize = { (context as? MainActivity)?.enterPipNow() },
                    onSwitchCamera = onSwitchCamera,
                    onMessage = { showMessages = true },
                    onShowInvite = { showInvite = true },
                    onLeave = { showLeaveDialog = true },
                )
                // Lobby banner: only renders for owners with someone in
                // the waiting queue (non-owners' waitingParticipants is
                // always empty, so the if-guard is one source of truth).
                if (state.waitingParticipants.isNotEmpty()) {
                    LobbyBanner(
                        count = state.waitingParticipants.size,
                        onClick = { showWaitingList = true },
                    )
                }
                if (state.isRecording) {
                    RecordingBanner()
                }
            }
        }

        // Subtitle overlay — sits just above the bottom toolbar when
        // the user has enabled captions. Always visible (regardless of
        // toolbar fade-out) so subtitles don't blink with the controls.
        if (state.subtitlesOverlayOn) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            ) {
                SubtitleOverlay(segments = subtitleSegments)
            }
        }

        // Bottom toolbar (drawer-style)
        AnimatedVisibility(
            visible = toolbarsVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomToolbar(
                micEnabled = state.micEnabled,
                cameraEnabled = state.cameraEnabled,
                audioOutput = audioOutput,
                onToggleMic = onToggleMic,
                onToggleCamera = onToggleCamera,
                onSpeakerClick = { showAudioSheet = true },
                onShowParticipants = { showParticipants = true },
                onShowMore = { showMore = true },
            )
        }
    }

    // Rename-self dialog. Driven from the Participants sheet's edit icon on
    // the local user's row. Kept here (not nested inside ParticipantsSheet)
    // so the dismiss + sheet teardown ordering stays predictable, and so
    // the dialog survives if the user navigates away from the sheet.
    var showRenameDialog by remember { mutableStateOf(false) }

    // Kick-confirmation candidate. The sheet immediately closes when the
    // host picks "踢出" so the confirm dialog reads on the meeting back-
    // drop, not under a half-opened sheet. `candidate` carries both
    // identity (for the API call) and display name (for the prompt).
    var kickCandidate by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Participants bottom sheet
    if (showParticipants) {
        ParticipantsSheet(
            participants = state.participants,
            isAdmin = state.isAdmin,
            onRenameSelfClick = {
                showParticipants = false
                showRenameDialog = true
            },
            onMuteClick = { identity ->
                scope.launch { onMuteParticipant(identity) }
            },
            onRemoveClick = { identity, name ->
                showParticipants = false
                kickCandidate = identity to name
            },
            onDismiss = { showParticipants = false },
        )
    }

    if (showRenameDialog) {
        val initialName = state.participants.firstOrNull { it.isLocal }?.name.orEmpty()
        RenameDialog(
            initial = initialName,
            onConfirm = { newName ->
                scope.launch { onRenameSelf(newName) }
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    kickCandidate?.let { (identity, name) ->
        KickConfirmDialog(
            participantName = name,
            onConfirm = {
                scope.launch { onRemoveParticipant(identity) }
                kickCandidate = null
            },
            onDismiss = { kickCandidate = null },
        )
    }

    if (showInvite) {
        InviteSheet(
            roomName = roomName,
            roomSlug = roomSlug,
            onDismiss = { showInvite = false },
        )
    }

    if (showWaitingList) {
        WaitingListSheet(
            waitingParticipants = state.waitingParticipants,
            onAdmit = { id ->
                scope.launch { onAdmitParticipant(id, true) }
            },
            onDeny = { id ->
                scope.launch { onAdmitParticipant(id, false) }
            },
            onAdmitAll = {
                state.waitingParticipants.forEach { p ->
                    scope.launch { onAdmitParticipant(p.id, true) }
                }
            },
            onDismiss = { showWaitingList = false },
        )
    }

    // More-actions bottom sheet (hand / share / record / interpret / settings)
    val handRaised = state.participants
        .firstOrNull { it.isLocal && !it.isScreenShare }
        ?.handRaisedAt != null
    if (showMore) {
        MoreActionsSheet(
            isAdmin = state.isAdmin,
            handRaised = handRaised,
            localScreenSharing = state.localScreenSharing,
            isRecording = state.isRecording,
            recordingPending = state.recordingPending,
            subtitlesOverlayOn = state.subtitlesOverlayOn,
            subtitlesPending = state.subtitlesPending,
            onRaiseHandClick = {
                showMore = false
                scope.launch { onToggleHand() }
            },
            onShareClick = {
                showMore = false
                if (state.localScreenSharing) {
                    onStopScreenShare()
                } else {
                    showShareChooser = true
                }
            },
            onRecordClick = {
                showMore = false
                onToggleRecording()
            },
            onSubtitlesClick = {
                showMore = false
                onToggleSubtitles()
            },
            onAiClick = {
                showMore = false
                showAiSheet = true
            },
            onHostSettingsClick = {
                showMore = false
                scope.launch { onRefreshAccessLevel() }
                showHostSettings = true
            },
            onDismiss = { showMore = false },
        )
    }

    if (showAiSheet) {
        RoomAiSheet(
            messages = aiMessages,
            asking = aiAsking,
            onSend = onAskAi,
            onClear = onClearAi,
            onDismiss = { showAiSheet = false },
        )
    }

    if (showHostSettings) {
        HostSettingsSheet(
            currentAccessLevel = state.accessLevel,
            onSelectAccessLevel = { level ->
                scope.launch { onUpdateAccessLevel(level) }
            },
            onDismiss = { showHostSettings = false },
        )
    }

    // Share chooser: screen vs. whiteboard. Matches Tencent Meeting's
    // "共享屏幕 / 共享白板" two-option sheet. The system MediaProjection dialog
    // handles the finer "entire screen / single app" selection natively.
    if (showShareChooser) {
        val comingSoon = stringResource(R.string.room_more_coming_soon)
        ScreenShareChooserSheet(
            onShareScreen = {
                showShareChooser = false
                // If we have never prompted for the overlay permission and
                // don't currently have it, send the user to the system
                // setting first — the desktop stop bubble only works with
                // SYSTEM_ALERT_WINDOW granted. After they come back, a
                // second tap on "共享屏幕" proceeds straight to capture.
                if (!overlayPermissionPrompted &&
                    !ScreenShareOverlay.canDrawOverlays(context)) {
                    overlayPermissionPrompted = true
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.room_screen_share_overlay_prompt),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                } else {
                    requestScreenCapture()
                }
            },
            onShareWhiteboard = {
                showShareChooser = false
                android.widget.Toast.makeText(
                    context, comingSoon, android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = { showShareChooser = false },
        )
    }

    // Audio output sheet
    if (showAudioSheet) {
        AudioOutputSheet(
            current = audioOutput,
            onSelect = { audioOutput = it; showAudioSheet = false },
            onDismiss = { showAudioSheet = false },
        )
    }

    // In-meeting messages full-screen panel
    if (showMessages) {
        MessagesPanel(
            messages = state.messages,
            onSend = onSendMessage,
            onDismiss = { showMessages = false },
        )
    }

    // Leave/End meeting dialog
    if (showLeaveDialog) {
        LeaveDialog(
            isAdmin = isAdmin,
            onLeave = { showLeaveDialog = false; onLeave() },
            onEndMeeting = { showLeaveDialog = false; onEndMeeting() },
            onDismiss = { showLeaveDialog = false },
        )
    }
}

// ── Top toolbar ──────────────────────────────────────────────────────────

@Composable
private fun TopToolbar(
    roomName: String,
    roomSlug: String,
    onMinimize: () -> Unit,
    onSwitchCamera: () -> Unit,
    onMessage: () -> Unit,
    onShowInvite: () -> Unit,
    onLeave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Left cluster: minimize + switch camera
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onMinimize,
                modifier = Modifier.size(RoomToolbarIconButtonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = stringResource(R.string.room_action_minimize),
                    tint = Color.White,
                    modifier = Modifier.size(RoomToolbarIconSize),
                )
            }
            IconButton(
                onClick = onSwitchCamera,
                modifier = Modifier.size(RoomToolbarIconButtonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraIos,
                    contentDescription = stringResource(R.string.room_action_switch_camera),
                    tint = Color.White,
                    modifier = Modifier.size(RoomToolbarIconSize),
                )
            }
        }

        // Center: room name + slug. Padding widened so the title never collides
        // with the two-icon left cluster or the message+end right cluster.
        // Tapping the cluster opens InviteSheet — feels-right discovery for
        // host (and any participant who wants to forward the link).
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 110.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onShowInvite)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = roomName.ifBlank { stringResource(R.string.room_title) },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
            )
            if (roomSlug.isNotBlank()) {
                Text(
                    text = stringResource(R.string.room_slug_label, roomSlug),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        // Right cluster: message + leave/end
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onMessage,
                modifier = Modifier.size(RoomToolbarIconButtonSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = stringResource(R.string.room_action_message),
                    tint = Color.White,
                    modifier = Modifier.size(RoomToolbarIconSize),
                )
            }
            IconButton(
                onClick = onLeave,
                modifier = Modifier.size(RoomToolbarIconButtonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = stringResource(R.string.room_end),
                    tint = Color(0xFFFF4444),
                    modifier = Modifier.size(RoomToolbarIconSize),
                )
            }
        }
    }
}

// ── Bottom toolbar ───────────────────────────────────────────────────────

@Composable
private fun BottomToolbar(
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    audioOutput: AudioOutput,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSpeakerClick: () -> Unit,
    onShowParticipants: () -> Unit,
    onShowMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                )
            )
            .navigationBarsPadding()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            icon = if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
            label = stringResource(R.string.room_action_mic),
            isOn = micEnabled,
            onClick = onToggleMic,
            modifier = Modifier.weight(1f),
            iconSize = RoomToolbarIconSize,
            iconButtonSize = BottomToolbarIconButtonSize,
            labelSpacing = BottomToolbarLabelSpacing,
        )
        ControlButton(
            icon = if (cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
            label = stringResource(R.string.room_action_camera),
            isOn = cameraEnabled,
            onClick = onToggleCamera,
            modifier = Modifier.weight(1f),
            iconSize = RoomToolbarIconSize,
            iconButtonSize = BottomToolbarIconButtonSize,
            labelSpacing = BottomToolbarLabelSpacing,
        )
        ControlButton(
            icon = when (audioOutput) {
                AudioOutput.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
                AudioOutput.Earpiece -> Icons.Default.Hearing
                AudioOutput.Mute -> Icons.AutoMirrored.Filled.VolumeOff
            },
            label = stringResource(R.string.preview_speaker),
            isOn = true,
            onClick = onSpeakerClick,
            modifier = Modifier.weight(1f),
            iconSize = RoomToolbarIconSize,
            iconButtonSize = BottomToolbarIconButtonSize,
            labelSpacing = BottomToolbarLabelSpacing,
        )
        ControlButton(
            icon = Icons.Default.People,
            label = stringResource(R.string.room_action_participants),
            isOn = true,
            onClick = onShowParticipants,
            modifier = Modifier.weight(1f),
            iconSize = RoomToolbarIconSize,
            iconButtonSize = BottomToolbarIconButtonSize,
            labelSpacing = BottomToolbarLabelSpacing,
        )
        ControlButton(
            icon = Icons.Default.MoreHoriz,
            label = stringResource(R.string.room_action_more),
            isOn = true,
            onClick = onShowMore,
            modifier = Modifier.weight(1f),
            iconSize = RoomToolbarIconSize,
            iconButtonSize = BottomToolbarIconButtonSize,
            labelSpacing = BottomToolbarLabelSpacing,
        )
    }
}

// ── Video grid ───────────────────────────────────────────────────────────

@Composable
private fun VideoGrid(
    state: RoomUiState,
    room: io.livekit.android.room.Room,
    showPinButtons: Boolean,
    onPin: (String) -> Unit,
    onUnpin: () -> Unit,
    onStopScreenShare: () -> Unit,
) {
    val participants = state.participants
    if (participants.isEmpty()) return

    val focus = state.focusIdentity?.takeIf { id -> participants.any { it.identity == id } }

    // Key by sessionGeneration so that a reconnect tears the tiles down and
    // re-mounts them. Without this, VideoTrackView's SurfaceView stays bound
    // to the pre-reconnect RTCVideoTrack and remote video freezes on its
    // last frame even though fresh publications are subscribed.
    androidx.compose.runtime.key(state.sessionGeneration) {
        if (focus != null) {
            FocusLayout(
                room = room,
                participants = participants,
                focusIdentity = focus,
                showPinButtons = showPinButtons,
                onPin = onPin,
                onUnpin = onUnpin,
                onStopScreenShare = onStopScreenShare,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GalleryLayout(
                room = room,
                participants = participants,
                focusIdentity = null,
                showPinButtons = showPinButtons,
                onPin = onPin,
                onStopScreenShare = onStopScreenShare,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ── Participants bottom sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(
    participants: List<ParticipantUi>,
    isAdmin: Boolean,
    onRenameSelfClick: () -> Unit,
    onMuteClick: (identity: String) -> Unit,
    onRemoveClick: (identity: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Text(
                text = stringResource(R.string.room_participants, participants.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            HorizontalDivider()
            LazyColumn {
                items(participants) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(6.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = p.name + if (p.isLocal) stringResource(R.string.room_participant_me) else "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        // Rename pencil — only on the local user's row. Web's
                        // /rename/ endpoint only ever renames the requesting
                        // participant (LiveKit-token identity), so we don't
                        // expose this on remote rows.
                        if (p.isLocal) {
                            IconButton(
                                onClick = onRenameSelfClick,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.room_rename_self),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = if (p.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (p.isMicEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFF6B6B),
                            modifier = Modifier.size(20.dp),
                        )
                        // Owner-only host actions menu. We skip screen-share
                        // synthetic rows — the sharer's camera row is the
                        // real participant and already owns these controls.
                        if (isAdmin && !p.isLocal && !p.isScreenShare) {
                            Spacer(Modifier.width(4.dp))
                            ParticipantHostMenu(
                                isMicEnabled = p.isMicEnabled,
                                onMute = { onMuteClick(p.identity) },
                                onRemove = { onRemoveClick(p.identity, p.name) },
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ParticipantHostMenu(
    isMicEnabled: Boolean,
    onMute: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.room_host_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.room_host_mute)) },
                enabled = isMicEnabled,
                onClick = {
                    menuOpen = false
                    onMute()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.room_host_remove),
                        color = Color(0xFFFF4444),
                    )
                },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
private fun KickConfirmDialog(
    participantName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_host_remove_title)) },
        text = { Text(stringResource(R.string.room_host_remove_message, participantName)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF4444)),
            ) { Text(stringResource(R.string.room_host_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.room_rename_cancel))
            }
        },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Compose holds the in-flight edit; we don't push back to the caller
    // until they confirm. Empty / whitespace-only is treated as cancel
    // (matches web's silent no-op rather than a noisy validation error).
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                singleLine = true,
                label = { Text(stringResource(R.string.room_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty() && value.trim() != initial.trim(),
            ) { Text(stringResource(R.string.room_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.room_rename_cancel))
            }
        },
    )
}

// ── Lobby banner + waiting list sheet (owner-only) ───────────────────────

@Composable
private fun LobbyBanner(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFB300))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.room_lobby_banner, count),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.room_lobby_banner_action),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Red "Recording in progress" pill shown at the top of every
 * participant's screen while LiveKit broadcasts `isRecording = true`.
 * Mirrors LobbyBanner's shape so the two stack cleanly when a host
 * is recording WITH visitors waiting in the lobby.
 */
@Composable
private fun RecordingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFF4444))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.FiberManualRecord,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.room_recording_banner),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaitingListSheet(
    waitingParticipants: List<com.we.meet.data.api.dto.WaitingParticipantDto>,
    onAdmit: (participantId: String) -> Unit,
    onDeny: (participantId: String) -> Unit,
    onAdmitAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.room_lobby_title, waitingParticipants.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (waitingParticipants.size > 1) {
                    TextButton(onClick = onAdmitAll) {
                        Text(stringResource(R.string.room_lobby_admit_all))
                    }
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(waitingParticipants) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(6.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = p.username,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onDeny(p.id) },
                        ) {
                            Text(
                                text = stringResource(R.string.room_lobby_deny),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { onAdmit(p.id) },
                        ) {
                            Text(stringResource(R.string.room_lobby_admit))
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Host settings sheet (owner-only) ──────────────────────────────────────

private const val ACCESS_LEVEL_PUBLIC = "public"
private const val ACCESS_LEVEL_TRUSTED = "trusted"
private const val ACCESS_LEVEL_RESTRICTED = "restricted"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSettingsSheet(
    currentAccessLevel: String?,
    onSelectAccessLevel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Text(
                text = stringResource(R.string.room_host_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.room_host_settings_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.room_host_access_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            AccessLevelOption(
                value = ACCESS_LEVEL_PUBLIC,
                current = currentAccessLevel,
                title = stringResource(R.string.room_host_access_public_title),
                description = stringResource(R.string.room_host_access_public_desc),
                onSelect = onSelectAccessLevel,
            )
            AccessLevelOption(
                value = ACCESS_LEVEL_TRUSTED,
                current = currentAccessLevel,
                title = stringResource(R.string.room_host_access_trusted_title),
                description = stringResource(R.string.room_host_access_trusted_desc),
                onSelect = onSelectAccessLevel,
            )
            AccessLevelOption(
                value = ACCESS_LEVEL_RESTRICTED,
                current = currentAccessLevel,
                title = stringResource(R.string.room_host_access_restricted_title),
                description = stringResource(R.string.room_host_access_restricted_desc),
                onSelect = onSelectAccessLevel,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccessLevelOption(
    value: String,
    current: String?,
    title: String,
    description: String,
    onSelect: (String) -> Unit,
) {
    val selected = current == value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelect(value) },
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── More-actions bottom sheet ────────────────────────────────────────────

/**
 * Feishu-style "更多" sheet. The Share entry is live (and flips to
 * "Stop share" while we're publishing a screen-share track); the other
 * entries are still MVP stubs that flash a "coming soon" toast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreActionsSheet(
    isAdmin: Boolean,
    handRaised: Boolean,
    localScreenSharing: Boolean,
    isRecording: Boolean,
    recordingPending: Boolean,
    subtitlesOverlayOn: Boolean,
    subtitlesPending: Boolean,
    onRaiseHandClick: () -> Unit,
    onShareClick: () -> Unit,
    onRecordClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onAiClick: () -> Unit,
    onHostSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    val comingSoon = stringResource(R.string.room_more_coming_soon)
    val showStub: () -> Unit = {
        android.widget.Toast.makeText(context, comingSoon, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Sheet-specific styling: the default ControlButton colours target the
    // dark in-meeting overlay (white label, semi-transparent white bg). On
    // the light ModalBottomSheet surface those become unreadable, so we feed
    // surface-aware colours here.
    val sheetBg = MaterialTheme.colorScheme.surfaceVariant
    val sheetTint = MaterialTheme.colorScheme.onSurface
    val shareTint = if (localScreenSharing) Color(0xFFFF4444) else sheetTint

    // Raised-hand visual: orange tint while raised, neutral while lowered.
    // Matches the desktop client's "primaryDark" selected state in spirit
    // without bringing in another colour role.
    val handTint = if (handRaised) Color(0xFFFFB300) else sheetTint
    val handBg = if (handRaised) Color(0xFFFFE6B3) else sheetBg

    // Subtitles — repurposes the old "interpret" slot. Anyone in the room
    // can flip them on; first-on POSTs start-subtitle (LiveKit-token auth,
    // see RoomRepository.startSubtitle). Once started the agent stays
    // attached for the meeting; subsequent taps only show/hide the overlay.
    val subtitlesTint = if (subtitlesOverlayOn) Color(0xFF3C8DFF) else sheetTint
    val subtitlesBg = if (subtitlesOverlayOn) Color(0xFFD9E8FF) else sheetBg

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 5-per-row grid. Each cell is `weight(1f)` so columns line up
        // between rows — keeps Settings (row 2 col 1) visually under
        // Raise Hand (row 1 col 1) without manual width math.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PanTool,
                    label = stringResource(
                        if (handRaised) R.string.room_more_lower_hand
                        else R.string.room_more_raise_hand
                    ),
                    isOn = true,
                    onClick = onRaiseHandClick,
                    labelColor = handTint,
                    iconBgColor = handBg,
                    iconTintColor = handTint,
                )
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = if (localScreenSharing) Icons.AutoMirrored.Filled.StopScreenShare
                        else Icons.AutoMirrored.Filled.ScreenShare,
                    label = stringResource(
                        if (localScreenSharing) R.string.room_screen_share_stop
                        else R.string.room_more_share
                    ),
                    isOn = true,
                    onClick = onShareClick,
                    labelColor = shareTint,
                    iconBgColor = sheetBg,
                    iconTintColor = shareTint,
                )
                // Record: backend RECORDING_ENABLE is off, stays a stub
                // ("功能开发中") for everyone. Banner still picks up
                // RecordingStatusChanged if Web ever flips it on.
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FiberManualRecord,
                    label = stringResource(R.string.room_more_record),
                    isOn = true,
                    onClick = showStub,
                    labelColor = sheetTint,
                    iconBgColor = sheetBg,
                    iconTintColor = sheetTint,
                )
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.ClosedCaption,
                    label = stringResource(
                        if (subtitlesOverlayOn) R.string.room_more_subtitles_off
                        else R.string.room_more_subtitles_on
                    ),
                    isOn = true,
                    onClick = if (subtitlesPending) showStub else onSubtitlesClick,
                    labelColor = subtitlesTint,
                    iconBgColor = subtitlesBg,
                    iconTintColor = subtitlesTint,
                )
                // In-room AI — backend gates on LiveKit token
                // (HasLiveKitRoomAccess) so all participants can use it.
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(R.string.room_more_ai),
                    isOn = true,
                    onClick = onAiClick,
                    labelColor = sheetTint,
                    iconBgColor = sheetBg,
                    iconTintColor = sheetTint,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                ControlButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Settings,
                    label = stringResource(R.string.room_more_settings),
                    isOn = true,
                    onClick = if (isAdmin) onHostSettingsClick else showStub,
                    labelColor = sheetTint,
                    iconBgColor = sheetBg,
                    iconTintColor = sheetTint,
                )
                // Reserve 4 empty cells so Settings keeps the col-1
                // position when more entries land on row 2 later.
                Spacer(Modifier.weight(4f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Screen-share chooser ─────────────────────────────────────────────────

/**
 * Tencent-Meeting-style two-option sheet: 共享屏幕 / 共享白板. The screen
 * option drops straight into the system MediaProjection consent dialog —
 * on Android 14+ that dialog already includes the "entire screen / single
 * app" toggle, so we don't need to build our own picker.
 *
 * Whiteboard is an MVP stub and just flashes a "coming soon" toast at the
 * call site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenShareChooserSheet(
    onShareScreen: () -> Unit,
    onShareWhiteboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            ShareOptionRow(
                icon = Icons.Default.PhoneAndroid,
                title = stringResource(R.string.room_screen_share_screen),
                onClick = onShareScreen,
            )
            HorizontalDivider()
            ShareOptionRow(
                icon = Icons.Default.Dashboard,
                title = stringResource(R.string.room_screen_share_whiteboard),
                onClick = onShareWhiteboard,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShareOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Leave dialog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveDialog(
    isAdmin: Boolean,
    onLeave: () -> Unit,
    onEndMeeting: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isAdmin) {
                Text(
                    text = stringResource(R.string.room_leave_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                HorizontalDivider()
            }

            // Leave meeting
            TextButton(
                onClick = onLeave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonHeight),
            ) {
                Text(
                    text = stringResource(R.string.room_leave),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // End meeting (red) - only for owner
            if (isAdmin) {
                HorizontalDivider()
                TextButton(
                    onClick = onEndMeeting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.ButtonHeight),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF4444)),
                ) {
                    Text(
                        text = stringResource(R.string.room_end_meeting),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Cancel
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Audio output ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioOutputSheet(
    current: AudioOutput,
    onSelect: (AudioOutput) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            AudioOutputOption(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = stringResource(R.string.preview_speaker),
                isSelected = current == AudioOutput.Speaker,
                onClick = { onSelect(AudioOutput.Speaker) },
            )
            HorizontalDivider()
            AudioOutputOption(
                icon = Icons.Default.Hearing,
                label = stringResource(R.string.preview_earpiece),
                isSelected = current == AudioOutput.Earpiece,
                onClick = { onSelect(AudioOutput.Earpiece) },
            )
            HorizontalDivider()
            AudioOutputOption(
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                label = stringResource(R.string.preview_mute),
                isSelected = current == AudioOutput.Mute,
                onClick = { onSelect(AudioOutput.Mute) },
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AudioOutputOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) Color(0xFF3366FF) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) Color(0xFF3366FF) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF3366FF),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ── Shared components ────────────────────────────────────────────────────

@Composable
internal fun ControlButton(
    icon: ImageVector,
    label: String,
    isOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = Color.White,
    iconBgColor: Color? = null,
    iconTintColor: Color? = null,
    iconSize: Dp = RoomToolbarIconSize,
    iconButtonSize: Dp = RoomToolbarIconButtonSize,
    labelSpacing: Dp = 6.dp,
) {
    // Default: transparent icon bg -> flat icon + label. Sheet callers pass
    // an explicit [iconBgColor] when they want the circular icon container.
    val bgColor = iconBgColor ?: Color.Transparent
    val iconTint = iconTintColor
        ?: if (isOn) Color.White else Color(0xFFFF6B6B)

    // Whole column is the click target — tap on the icon, the label, or
    // the space between all route to [onClick]. The caller-supplied
    // [modifier] lets the parent Row give each button a weighted slot so
    // neighbouring buttons butt up edge-to-edge, leaving no gaps through
    // which a tap could leak to a page-level clickable (e.g. the
    // toolbar-toggle wrapper).
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(iconButtonSize)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(Modifier.height(labelSpacing))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
    }
}

@Composable
private fun ConnectingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.room_connecting), color = Color.White)
        }
    }
}

@Composable
private fun ErrorView(message: String?, onLeave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message ?: stringResource(R.string.error_unknown),
                color = Color(0xFFFF6B6B),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onLeave,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEE4444)),
            ) {
                Text(stringResource(R.string.room_action_hangup), color = Color.White)
            }
        }
    }
}
