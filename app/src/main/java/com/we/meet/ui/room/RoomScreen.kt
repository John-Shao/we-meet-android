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
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.we.meet.audio.AudioOutput
import com.we.meet.audio.AudioOutputController
import com.we.meet.audio.AudioOutputStore
import com.we.meet.overlay.ScreenShareOverlay
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
            TopToolbar(
                roomName = roomName,
                roomSlug = roomSlug,
                onMinimize = { (context as? MainActivity)?.enterPipNow() },
                onSwitchCamera = onSwitchCamera,
                onMessage = { showMessages = true },
                onLeave = { showLeaveDialog = true },
            )
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

    // Participants bottom sheet
    if (showParticipants) {
        ParticipantsSheet(
            participants = state.participants,
            onDismiss = { showParticipants = false },
        )
    }

    // More-actions bottom sheet (share / record / interpret / settings)
    if (showMore) {
        MoreActionsSheet(
            localScreenSharing = state.localScreenSharing,
            onShareClick = {
                showMore = false
                if (state.localScreenSharing) {
                    onStopScreenShare()
                } else {
                    showShareChooser = true
                }
            },
            onDismiss = { showMore = false },
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
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 110.dp),
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
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                        Icon(
                            imageVector = if (p.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (p.isMicEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFF6B6B),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
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
    localScreenSharing: Boolean,
    onShareClick: () -> Unit,
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ControlButton(
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
            ControlButton(
                icon = Icons.Default.FiberManualRecord,
                label = stringResource(R.string.room_more_record),
                isOn = true,
                onClick = showStub,
                labelColor = sheetTint,
                iconBgColor = sheetBg,
                iconTintColor = sheetTint,
            )
            ControlButton(
                icon = Icons.Default.Language,
                label = stringResource(R.string.room_more_interpret),
                isOn = true,
                onClick = showStub,
                labelColor = sheetTint,
                iconBgColor = sheetBg,
                iconTintColor = sheetTint,
            )
            ControlButton(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.room_more_settings),
                isOn = true,
                onClick = showStub,
                labelColor = sheetTint,
                iconBgColor = sheetBg,
                iconTintColor = sheetTint,
            )
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
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                .padding(horizontal = 16.dp),
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
                    .height(52.dp),
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
                        .height(52.dp),
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
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
