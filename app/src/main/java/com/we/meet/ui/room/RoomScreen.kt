package com.we.meet.ui.room

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.BuildConfig
import com.we.meet.LocalIsInPipMode
import com.we.meet.MainActivity
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.design.R as DesignR
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.components.WeMeetInlineErrorState
import com.we.meet.audio.AudioOutput
import com.we.meet.audio.AudioOutputController
import com.we.meet.audio.AudioOutputStore
import androidx.compose.runtime.saveable.rememberSaveable
import com.we.meet.core.directory.DirectoryDeps
import com.we.meet.core.directory.ui.ContactPicker
import com.we.meet.core.directory.ui.ContactPickerMode
import com.we.meet.core.directory.ui.MemberAvatar
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

private val RoomToolbarIconButtonSize = Dimens.Room.ToolbarIconButton
private val RoomToolbarIconSize = Dimens.IconMedium
// Bottom toolbar: container matches the icon exactly so the only gap
// between icon and label is BottomToolbarLabelSpacing. A larger container
// (e.g. the top toolbar's 40dp) adds invisible padding that pushes the
// label too far below.
private val BottomToolbarIconButtonSize = Dimens.IconMedium
private val BottomToolbarLabelSpacing = Dimens.SpaceXxs

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
                RoomUiState.Phase.Connecting -> ConnectingView(onCancel = { onLeave(false) })
                RoomUiState.Phase.Error -> ErrorView(
                    message = state.errorMessage,
                    onRetry = { viewModel.retry() },
                    onLeave = { onLeave(false) },
                )
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
                        // P4-M3 起不限 call 入口:普通会议里拉人同样广播,
                        // 全员的会议内 chips 可见「谁在被邀请」(对齐 Web)。
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
                                                // P5: lets receivers' suggested tab
                                                // match this ring to a person row.
                                                put("userId", inv.userId)
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

                    // 拍板(2026-07-17 三次): 视频切会议页的时机 = 第三人真正
                    // 进房(远端≥2),发邀/响铃期间双方都留在 1:1 视频页。
                    LaunchedEffect(remoteCount) {
                        if (remoteCount >= 2) upgradeLatch = true
                    }

                    // M2: owner-side rename once the call truly became
                    // multi-party (meeting history stops showing「与X的通话」).
                    // ONLY for the 1:1-escalation entry (callPeerUid) — group
                    // voice rooms are already named「{群名}的语音通话」(P4.1).
                    var renamed by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(remoteCount) {
                        if (renamed || callPeerUid == null || !isAdmin || remoteCount < 2) {
                            return@LaunchedEffect
                        }
                        renamed = true
                        // Token participant name can be blank/synthetic for
                        // call-flow entrants — the cached directory nickname
                        // (users/me full_name) is the reliable fallback.
                        val selfName = (context.applicationContext as WeMeetApp)
                            .tokenStore.nickname?.takeIf { it.isNotBlank() }
                            ?: state.participants.firstOrNull { it.isLocal }?.name.orEmpty()
                        if (selfName.isNotBlank()) {
                            viewModel.renameRoom(
                                context.getString(
                                    com.we.meet.feature.im.R.string.im_meet_invite_room_name,
                                    selfName,
                                ),
                            )
                        }
                    }

                    // P4.1 会议拉人 picker visibility (hosted at this level so
                    // it can reach imSession + roomSlug). P5.1: seeded with the
                    // participants-sheet search query (实测问题3).
                    var showMeetingInvitePicker by rememberSaveable { mutableStateOf(false) }
                    var invitePickerQuery by rememberSaveable { mutableStateOf("") }

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
                        // P4-M3 会议内 chips:本端(排除 已接受/已取消)+ 他人
                        // 广播的响铃快照,统一 (label, stateKey, avatarUrl)。
                        val overlayChips = remember(invites, remoteChips) {
                            buildList {
                                invites.forEach { inv ->
                                    if (inv.state != MeetInviteTracker.InviteState.ACCEPTED &&
                                        inv.state != MeetInviteTracker.InviteState.CANCELED
                                    ) {
                                        add(
                                            Triple(
                                                inv.label,
                                                inv.state.name.lowercase(),
                                                inv.avatarUrl,
                                            ),
                                        )
                                    }
                                }
                                addAll(remoteChips)
                            }
                        }
                        // P5 建议参会: invited list + presence-diff signals for
                        // the participants sheet's suggested tab.
                        val suggestedAll by viewModel.suggestedParticipants
                            .collectAsStateWithLifecycle()
                        val remoteRingingMap by viewModel.remoteRingingUserIds
                            .collectAsStateWithLifecycle()
                        val remoteRingingIds = remember(remoteRingingMap, presentIds) {
                            remoteRingingMap
                                .filterKeys { it in presentIds }
                                .values.flatten().toSet()
                        }
                        val callSuggested = { person: com.we.meet.data.api.dto.SuggestedParticipantDto ->
                            val selfName = (context.applicationContext as WeMeetApp)
                                .tokenStore.nickname?.takeIf { it.isNotBlank() }
                                ?: state.participants.firstOrNull { it.isLocal }?.name.orEmpty()
                            imSession.meetInvites.sendInvites(
                                targets = listOf(
                                    MeetInviteTracker.Target(
                                        person.id, person.displayName, person.avatarUrl,
                                    ),
                                ),
                                media = "video",
                                roomSlug = roomSlug,
                                roomName = context.getString(
                                    com.we.meet.feature.im.R.string.im_meet_invite_room_name,
                                    selfName,
                                ),
                            )
                            // 幂等上报——再呼/首呼都保持名单最新。
                            viewModel.reportSuggestedParticipants(listOf(person.id), "manual")
                        }
                        RoomContent(
                        state = state,
                        room = viewModel.room,
                        meetInviteChips = overlayChips,
                        pinPreferredAudioDevice = viewModel.callAudioDeviceModule::setPreferredDevice,
                        roomName = roomName,
                        roomSlug = roomSlug,
                        isAdmin = isAdmin,
                        suggestedParticipants = suggestedAll,
                        myInvites = invites,
                        remoteRingingUserIds = remoteRingingIds,
                        onCallSuggested = callSuggested,
                        onCancelInvite = { imSession.meetInvites.cancelOne(it) },
                        onRefreshSuggested = viewModel::refreshSuggestedParticipants,
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
                        onInviteMembers = { q ->
                            invitePickerQuery = q
                            showMeetingInvitePicker = true
                        },
                    )
                    // P4.1 会议拉人: org-member picker → parallel kind=meet
                    // ringing invites into THIS meeting (media=video → the
                    // invitee accepts straight into the full meeting UI).
                    if (showMeetingInvitePicker) {
                        // P5 统一邀请面板:选人振铃 + 底部会议号/复制链接/分享
                        // 合一(footer slot);确认即幂等上报建议名单。
                        ContactPicker(
                            deps = context.applicationContext as DirectoryDeps,
                            mode = ContactPickerMode.Multi,
                            footer = { UnifiedInviteFooter(roomSlug = roomSlug) },
                            initialQuery = invitePickerQuery,
                            onConfirm = { picked ->
                                showMeetingInvitePicker = false
                                if (picked.isNotEmpty()) {
                                    val selfName = (context.applicationContext as WeMeetApp)
                                        .tokenStore.nickname?.takeIf { it.isNotBlank() }
                                        ?: state.participants
                                            .firstOrNull { it.isLocal }?.name.orEmpty()
                                    imSession.meetInvites.sendInvites(
                                        targets = picked.map {
                                            MeetInviteTracker.Target(
                                                it.userId, it.displayName, it.avatarUrl,
                                            )
                                        },
                                        media = "video",
                                        roomSlug = roomSlug,
                                        roomName = context.getString(
                                            com.we.meet.feature.im.R.string.im_meet_invite_room_name,
                                            selfName,
                                        ),
                                    )
                                    viewModel.reportSuggestedParticipants(
                                        picked.map { it.userId }, "manual",
                                    )
                                }
                            },
                            onDismiss = { showMeetingInvitePicker = false },
                        )
                    }
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
        val remoteCountNow = state.participants.count { !it.isLocal }
        if (remoteCountNow > 0) {
            // P4.1: stamps the group-call connect instant (no-op otherwise)
            // so the group record's duration excludes the ring wait.
            imSession.calls.markGroupCallConnected()
        }
        if (pendingInvites > 0) return@LaunchedEffect
        if (remoteCountNow > 0) {
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
            // P5 统一邀请面板:通话舞台入口同样带会议号/复制链接区。
            footer = { UnifiedInviteFooter(roomSlug = roomSlug) },
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
                    // P5 建议参会:拉人即幂等上报,未接者可在参会人页再呼。
                    viewModel.reportSuggestedParticipants(
                        picked.map { it.userId }, "manual",
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

/**
 * P4-M3: 完整会议 UI 顶部的邀请状态悬浮 chips——会议页没有语音宫格的占位
 * 瓦片,拉人后本端毫无反馈,这里补上「响铃中/邀请中」与本端终态(已拒绝/
 * 无应答/忙线)。只读轻量,重邀走「更多 → 邀请成员」。
 */
@Composable
private fun MeetInviteChipsRow(
    chips: List<Triple<String, String, String?>>,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun stateLabel(key: String): String = when (key) {
        "ringing" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_ringing)
        "inviting" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_inviting)
        "rejected" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_rejected)
        "busy" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_busy)
        "unreachable" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_unreachable)
        "timeout" -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_timeout)
        else -> stringResource(com.we.meet.feature.im.R.string.im_call_invite_failed)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        modifier = modifier
            .padding(horizontal = Dimens.SpaceL)
            .horizontalScroll(rememberScrollState()),
    ) {
        chips.forEach { (label, stateKey, _) ->
            val active = stateKey == "ringing" || stateKey == "inviting"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        CircleShape,
                    )
                    .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Spacer(Modifier.width(Dimens.SpaceXs))
                Text(
                    text = stateLabel(stateKey),
                    color = if (active) {
                        WeMeetTheme.extras.room.overlayAccentText
                    } else {
                        Color.White.copy(alpha = 0.65f)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
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
    onRenameSelf: suspend (String) -> Result<Unit>,
    onToggleHand: suspend () -> Result<Unit>,
    onMuteParticipant: suspend (String) -> Result<Unit>,
    onRemoveParticipant: suspend (String) -> Result<Unit>,
    onAdmitParticipant: suspend (participantId: String, allow: Boolean) -> Result<Unit>,
    onRefreshAccessLevel: suspend () -> Result<Unit>,
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
    /** P4.1 会议拉人: opens the org-member ringing picker (RoomScreen hosts
     * it). P5.1: carries the participants-sheet search query so the picker
     * opens pre-seeded (实测问题3:输入不白打). */
    onInviteMembers: (query: String) -> Unit = {},
    /** P4-M3 会议内邀请状态 chips:(label, stateKey, avatarUrl)。 */
    meetInviteChips: List<Triple<String, String, String?>> = emptyList(),
    // ---- P5 建议参会 (participants sheet suggested tab) ----
    /** Raw invited list — the sheet subtracts present subs itself. */
    suggestedParticipants: List<com.we.meet.data.api.dto.SuggestedParticipantDto> = emptyList(),
    /** My own invite lifecycle rows (per-person 呼叫中/终态). */
    myInvites: List<MeetInviteTracker.MeetInvite> = emptyList(),
    /** userIds co-participants are actively ringing (broadcast userId). */
    remoteRingingUserIds: Set<String> = emptySet(),
    onCallSuggested: (com.we.meet.data.api.dto.SuggestedParticipantDto) -> Unit = {},
    onCancelInvite: (callId: String) -> Unit = {},
    onRefreshSuggested: () -> Unit = {},
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
    var waitingActionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var admittingAll by remember { mutableStateOf(false) }
    val lobbyActionFailedText = stringResource(R.string.room_lobby_action_failed)
    var showHostSettings by remember { mutableStateOf(false) }
    var accessLoading by remember { mutableStateOf(false) }
    var accessLoadFailed by remember { mutableStateOf(false) }
    var accessUpdating by remember { mutableStateOf(false) }
    val accessUpdateFailedText = stringResource(R.string.room_host_settings_save_failed)
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
    val gap = Dimens.SpaceS
    val topInset by animateDpAsState(
        targetValue =
            if (toolbarsVisible) statusTop + Dimens.Room.TopToolbarHeight + gap else Dimens.SpaceNone,
        label = "topInset",
    )
    val bottomInset by animateDpAsState(
        targetValue =
            if (toolbarsVisible) navBottom + Dimens.Room.BottomToolbarHeight + gap else Dimens.SpaceNone,
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

        // P4-M3 邀请状态悬浮 chips(响铃中/终态,只读;重邀走「邀请成员」)。
        if (meetInviteChips.isNotEmpty()) {
            MeetInviteChipsRow(
                chips = meetInviteChips,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset + Dimens.SpaceS),
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
                    .padding(bottom = Dimens.Room.SubtitleBottomInset),
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
    var renameSaving by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf(false) }

    // Kick-confirmation candidate. The sheet immediately closes when the
    // host picks "踢出" so the confirm dialog reads on the meeting back-
    // drop, not under a half-opened sheet. `candidate` carries both
    // identity (for the API call) and display name (for the prompt).
    var kickCandidate by remember { mutableStateOf<Pair<String, String>?>(null) }
    var kickSaving by remember { mutableStateOf(false) }
    val removeFailedText = stringResource(R.string.room_host_remove_failed)

    // Participants bottom sheet
    if (showParticipants) {
        ParticipantsSheet(
            participants = state.participants,
            isAdmin = state.isAdmin,
            suggested = suggestedParticipants,
            myInvites = myInvites,
            remoteRingingUserIds = remoteRingingUserIds,
            onCallSuggested = onCallSuggested,
            onCancelInvite = onCancelInvite,
            onRefreshSuggested = onRefreshSuggested,
            onInviteMembers = { q ->
                showParticipants = false
                onInviteMembers(q)
            },
            onRenameSelfClick = {
                showParticipants = false
                renameError = false
                showRenameDialog = true
            },
            onMuteClick = { identity ->
                scope.launch { onMuteParticipant(identity) }
            },
            onRemoveClick = { identity, name ->
                showParticipants = false
                kickSaving = false
                kickCandidate = identity to name
            },
            onDismiss = { showParticipants = false },
        )
    }

    if (showRenameDialog) {
        val initialName = state.participants.firstOrNull { it.isLocal }?.name.orEmpty()
        RenameDialog(
            initial = initialName,
            inFlight = renameSaving,
            error = renameError,
            onConfirm = { newName ->
                if (!renameSaving) {
                    renameSaving = true
                    renameError = false
                    scope.launch {
                        onRenameSelf(newName)
                            .onSuccess {
                                renameSaving = false
                                showRenameDialog = false
                            }
                            .onFailure {
                                renameSaving = false
                                renameError = true
                            }
                    }
                }
            },
            onDismiss = { if (!renameSaving) showRenameDialog = false },
        )
    }

    kickCandidate?.let { (identity, name) ->
        KickConfirmDialog(
            participantName = name,
            inFlight = kickSaving,
            onConfirm = {
                if (!kickSaving) {
                    kickSaving = true
                    scope.launch {
                        onRemoveParticipant(identity)
                            .onSuccess {
                                kickSaving = false
                                kickCandidate = null
                            }
                            .onFailure {
                                kickSaving = false
                                Toast.makeText(
                                    context,
                                    removeFailedText,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                }
            },
            onDismiss = { if (!kickSaving) kickCandidate = null },
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
            pendingIds = waitingActionIds,
            admittingAll = admittingAll,
            onAdmit = { id ->
                if (id !in waitingActionIds) {
                    waitingActionIds += id
                    scope.launch {
                        onAdmitParticipant(id, true).onFailure {
                            Toast.makeText(
                                context,
                                lobbyActionFailedText,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        waitingActionIds -= id
                    }
                }
            },
            onDeny = { id ->
                if (id !in waitingActionIds) {
                    waitingActionIds += id
                    scope.launch {
                        onAdmitParticipant(id, false).onFailure {
                            Toast.makeText(
                                context,
                                lobbyActionFailedText,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        waitingActionIds -= id
                    }
                }
            },
            onAdmitAll = {
                if (!admittingAll) {
                    val participantIds = state.waitingParticipants.map { it.id }
                    admittingAll = true
                    waitingActionIds += participantIds
                    scope.launch {
                        val failed = participantIds.count { id ->
                            onAdmitParticipant(id, true).isFailure
                        }
                        waitingActionIds -= participantIds.toSet()
                        admittingAll = false
                        if (failed > 0) {
                            Toast.makeText(
                                context,
                                lobbyActionFailedText,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
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
                showHostSettings = true
                accessLoading = true
                accessLoadFailed = false
                scope.launch {
                    onRefreshAccessLevel()
                        .onFailure { accessLoadFailed = true }
                    accessLoading = false
                }
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
            loading = accessLoading,
            loadFailed = accessLoadFailed,
            updating = accessUpdating,
            onRetry = {
                if (!accessLoading) {
                    accessLoading = true
                    accessLoadFailed = false
                    scope.launch {
                        onRefreshAccessLevel()
                            .onFailure { accessLoadFailed = true }
                        accessLoading = false
                    }
                }
            },
            onSelectAccessLevel = { level ->
                if (!accessUpdating) {
                    accessUpdating = true
                    scope.launch {
                        onUpdateAccessLevel(level)
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    accessUpdateFailedText,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        accessUpdating = false
                    }
                }
            },
            onDismiss = {
                if (!accessLoading && !accessUpdating) showHostSettings = false
            },
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
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
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
                .padding(horizontal = Dimens.Room.TitleSideInset)
                .clip(RoundedCornerShape(Dimens.CornerS))
                .clickable(onClick = onShowInvite)
                .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
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
                    tint = WeMeetTheme.extras.room.dangerOnOverlay,
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
            .padding(vertical = Dimens.SpaceXs),
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
    // ---- P5 建议参会 (设计 §6.1,对齐 Web §5.1) ----
    suggested: List<com.we.meet.data.api.dto.SuggestedParticipantDto> = emptyList(),
    myInvites: List<MeetInviteTracker.MeetInvite> = emptyList(),
    remoteRingingUserIds: Set<String> = emptySet(),
    onCallSuggested: (com.we.meet.data.api.dto.SuggestedParticipantDto) -> Unit = {},
    onCancelInvite: (callId: String) -> Unit = {},
    onRefreshSuggested: () -> Unit = {},
    /** P5.1: carries this sheet's search query into the invite picker. */
    onInviteMembers: (query: String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState()

    // Refresh the invited list whenever the sheet opens — cheap GET, and it
    // keeps re-calls/joins from other devices from going stale.
    LaunchedEffect(Unit) { onRefreshSuggested() }

    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    // Presence diff: a suggestion whose sub is a live identity has "moved to
    // the 全部 tab" (screen-share synthetic identities carry a #screen suffix
    // and never collide with subs).
    val presentSubs = participants.map { it.identity }.toSet()
    val pendingSuggested = suggested.filter { !it.isSelf && it.sub !in presentSubs }

    val q = query.trim()
    val shownParticipants =
        if (q.isEmpty()) participants
        else participants.filter { it.name.contains(q, ignoreCase = true) }
    val shownSuggested =
        if (q.isEmpty()) pendingSuggested
        else pendingSuggested.filter { person ->
            listOfNotNull(person.fullName, person.shortName, person.email)
                .any { it.contains(q, ignoreCase = true) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            // P5: search-or-call box + invite button (Feishu-style header).
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.room_search_or_call)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dimens.SpaceS))
                Button(onClick = { onInviteMembers(query.trim()) }) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                    Spacer(Modifier.width(Dimens.SpaceXs))
                    Text(stringResource(R.string.room_invite_action))
                }
            }
            Spacer(Modifier.height(Dimens.SpaceS))
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = {
                        Text(stringResource(R.string.room_tab_all, participants.size))
                    },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            stringResource(
                                R.string.room_tab_suggested, pendingSuggested.size,
                            ),
                        )
                    },
                )
            }
            if (tab == 1) {
                // ---- 建议参会 tab:受邀未到者,逐人呼叫/取消/再呼 ----
                if (shownSuggested.isEmpty()) {
                    Text(
                        text = stringResource(R.string.room_suggested_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.SpaceXl),
                    )
                } else {
                    LazyColumn {
                        items(shownSuggested, key = { it.id }) { person ->
                            SuggestedParticipantRow(
                                person = person,
                                // Latest invite per user wins — re-calls push a
                                // fresh entry for the same person.
                                invite = myInvites.lastOrNull { it.userId == person.id },
                                remoteRinging = person.id in remoteRingingUserIds,
                                onCall = { onCallSuggested(person) },
                                onCancel = onCancelInvite,
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(shownParticipants) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .size(Dimens.AvatarS)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(Dimens.SpaceXs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Dimens.SpaceM))
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
                                modifier = Modifier.size(Dimens.MinTouchTarget),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.room_rename_self),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.IconSmall),
                                )
                            }
                            Spacer(Modifier.width(Dimens.SpaceXs))
                        }
                        Icon(
                            imageVector = if (p.isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = stringResource(
                                if (p.isMicEnabled) R.string.cd_mic_on else R.string.cd_mic_off,
                            ),
                            tint = if (p.isMicEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else WeMeetTheme.extras.status.danger,
                            modifier = Modifier.size(Dimens.IconSmall),
                        )
                        // Owner-only host actions menu. We skip screen-share
                        // synthetic rows — the sharer's camera row is the
                        // real participant and already owns these controls.
                        if (isAdmin && !p.isLocal && !p.isScreenShare) {
                            Spacer(Modifier.width(Dimens.SpaceXs))
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
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

/**
 * P5: one row of the 建议参会 tab — avatar / name / title·department on the
 * left, the per-person call lifecycle on the right:
 *   idle → 〔呼叫〕;  mine in-flight → 呼叫中/响铃中…〔✕〕;
 *   someone else ringing them → dimmed 响铃中;  my terminal → state +〔再呼〕.
 */
@Composable
private fun SuggestedParticipantRow(
    person: com.we.meet.data.api.dto.SuggestedParticipantDto,
    invite: MeetInviteTracker.MeetInvite?,
    remoteRinging: Boolean,
    onCall: () -> Unit,
    onCancel: (callId: String) -> Unit,
) {
    val mineActive = invite != null && !invite.terminal
    val mineEnded = invite != null && invite.terminal &&
        invite.state != MeetInviteTracker.InviteState.ACCEPTED &&
        invite.state != MeetInviteTracker.InviteState.CANCELED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (remoteRinging && !mineActive) 0.55f else 1f)
            .padding(vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(
            name = person.displayName,
            url = person.avatarUrl,
            cacheKey = "avatar:${person.id}",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.SpaceM),
        ) {
            Text(
                person.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                person.title?.takeIf { it.isNotBlank() },
                person.department?.name?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            mineActive -> {
                Text(
                    text = stringResource(
                        if (invite!!.state == MeetInviteTracker.InviteState.RINGING) {
                            R.string.room_invite_state_ringing
                        } else {
                            R.string.room_invite_state_inviting
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(
                    onClick = { onCancel(invite.callId) },
                    modifier = Modifier.size(Dimens.MinTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.room_call_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
            }
            remoteRinging -> Text(
                text = stringResource(R.string.room_invite_state_ringing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (mineEnded) {
                    Text(
                        text = stringResource(
                            when (invite!!.state) {
                                MeetInviteTracker.InviteState.REJECTED ->
                                    R.string.room_invite_state_rejected
                                MeetInviteTracker.InviteState.BUSY ->
                                    R.string.room_invite_state_busy
                                MeetInviteTracker.InviteState.UNREACHABLE ->
                                    R.string.room_invite_state_unreachable
                                MeetInviteTracker.InviteState.TIMEOUT ->
                                    R.string.room_invite_state_timeout
                                else -> R.string.room_invite_state_failed
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Dimens.SpaceXs),
                    )
                }
                OutlinedButton(
                    onClick = onCall,
                    contentPadding = PaddingValues(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
                ) {
                    Text(
                        stringResource(
                            if (mineEnded) R.string.room_call_again else R.string.room_call_one,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * P5 统一邀请面板 footer — the share fallbacks pinned under the picker list:
 * meeting code + copy-link + system share (mirrors Web's UnifiedInvitePanel;
 * the QR code stays on the top-bar InviteSheet, 渐进收敛 §3.3).
 */
@Composable
private fun UnifiedInviteFooter(roomSlug: String) {
    val context = LocalContext.current
    val baseUrl = BuildConfig.WE_MEET_BASE_URL.trimEnd('/')
    val joinUrl = "$baseUrl/$roomSlug"
    val inviteText = stringResource(R.string.invite_clipboard_format, joinUrl, roomSlug)
    val copiedToast = stringResource(R.string.invite_copied)
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.room_meeting_code_label, roomSlug),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("we-meet", inviteText))
                Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
            },
        ) { Text(stringResource(R.string.invite_copy_link)) }
        TextButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }
                context.startActivity(
                    Intent.createChooser(
                        send,
                        context.getString(R.string.invite_share_chooser_title),
                    ),
                )
            },
        ) { Text(stringResource(R.string.invite_share)) }
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
            modifier = Modifier.size(Dimens.MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.room_host_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
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
                        color = WeMeetTheme.extras.status.danger,
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
    inFlight: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text(stringResource(R.string.room_host_remove_title)) },
        text = { Text(stringResource(R.string.room_host_remove_message, participantName)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !inFlight,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = WeMeetTheme.extras.status.danger,
                ),
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                        color = WeMeetTheme.extras.status.danger,
                    )
                } else {
                    Text(stringResource(R.string.room_host_remove))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text(stringResource(R.string.room_rename_cancel))
            }
        },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    inFlight: Boolean,
    error: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Compose holds the in-flight edit; we don't push back to the caller
    // until they confirm. Empty / whitespace-only is treated as cancel
    // (matches web's silent no-op rather than a noisy validation error).
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text(stringResource(R.string.room_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(80) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.room_rename_label)) },
                    enabled = !inFlight,
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.room_rename_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = !inFlight && value.trim().isNotEmpty() && value.trim() != initial.trim(),
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSmall),
                        strokeWidth = Dimens.BorderEmphasis,
                    )
                } else {
                    Text(stringResource(R.string.room_rename_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
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
            .padding(horizontal = Dimens.SpaceM)
            .clip(RoundedCornerShape(Dimens.CornerS))
            .background(WeMeetTheme.extras.room.warningFill)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onFill = WeMeetTheme.extras.room.onWarningFill
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = onFill,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.room_lobby_banner, count),
            color = onFill,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.room_lobby_banner_action),
            color = onFill,
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
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs)
            .clip(RoundedCornerShape(Dimens.CornerS))
            .background(WeMeetTheme.extras.room.dangerFill)
            .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onFill = WeMeetTheme.extras.room.onDangerFill
        Icon(
            imageVector = Icons.Default.FiberManualRecord,
            contentDescription = null,
            tint = onFill,
            modifier = Modifier.size(Dimens.IconTiny),
        )
        Spacer(Modifier.width(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.room_recording_banner),
            color = onFill,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaitingListSheet(
    waitingParticipants: List<com.we.meet.data.api.dto.WaitingParticipantDto>,
    pendingIds: Set<String>,
    admittingAll: Boolean,
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
                    .padding(bottom = Dimens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.room_lobby_title, waitingParticipants.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (waitingParticipants.size > 1) {
                    TextButton(onClick = onAdmitAll, enabled = !admittingAll) {
                        if (admittingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.IconSmall),
                                strokeWidth = Dimens.BorderEmphasis,
                            )
                        } else {
                            Text(stringResource(R.string.room_lobby_admit_all))
                        }
                    }
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(waitingParticipants) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .size(Dimens.AvatarS)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(Dimens.SpaceXs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Dimens.SpaceM))
                        Text(
                            text = p.username,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.id in pendingIds) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(horizontal = Dimens.SpaceL)
                                    .size(Dimens.IconSmall),
                                strokeWidth = Dimens.BorderEmphasis,
                            )
                        } else {
                            TextButton(
                                onClick = { onDeny(p.id) },
                            ) {
                                Text(
                                    text = stringResource(R.string.room_lobby_deny),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(Dimens.SpaceXs))
                            TextButton(
                                onClick = { onAdmit(p.id) },
                            ) {
                                Text(stringResource(R.string.room_lobby_admit))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
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
    loading: Boolean,
    loadFailed: Boolean,
    updating: Boolean,
    onRetry: () -> Unit,
    onSelectAccessLevel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!loading && !updating) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Text(
                text = stringResource(R.string.room_host_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = Dimens.SpaceXs),
            )
            Text(
                text = stringResource(R.string.room_host_settings_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.SpaceL),
            )
            HorizontalDivider()
            when {
                loadFailed -> WeMeetInlineErrorState(
                    onRetry = onRetry,
                    message = stringResource(R.string.room_host_settings_load_failed),
                )

                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.SpaceXl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconMedium),
                        strokeWidth = Dimens.BorderEmphasis,
                    )
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.room_host_access_section),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .padding(vertical = Dimens.SpaceS)
                                .weight(1f),
                        )
                        if (updating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.IconSmall),
                                strokeWidth = Dimens.BorderEmphasis,
                            )
                        }
                    }
                    AccessLevelOption(
                        value = ACCESS_LEVEL_PUBLIC,
                        current = currentAccessLevel,
                        title = stringResource(R.string.room_host_access_public_title),
                        description = stringResource(R.string.room_host_access_public_desc),
                        enabled = !updating,
                        onSelect = onSelectAccessLevel,
                    )
                    AccessLevelOption(
                        value = ACCESS_LEVEL_TRUSTED,
                        current = currentAccessLevel,
                        title = stringResource(R.string.room_host_access_trusted_title),
                        description = stringResource(R.string.room_host_access_trusted_desc),
                        enabled = !updating,
                        onSelect = onSelectAccessLevel,
                    )
                    AccessLevelOption(
                        value = ACCESS_LEVEL_RESTRICTED,
                        current = currentAccessLevel,
                        title = stringResource(R.string.room_host_access_restricted_title),
                        description = stringResource(R.string.room_host_access_restricted_desc),
                        enabled = !updating,
                        onSelect = onSelectAccessLevel,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@Composable
private fun AccessLevelOption(
    value: String,
    current: String?,
    title: String,
    description: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val selected = current == value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(value) }
            .padding(vertical = Dimens.SpaceS),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelect(value) },
            enabled = enabled,
        )
        Spacer(Modifier.width(Dimens.SpaceS))
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
    // Distinct messages for states that aren't actually "unimplemented": a
    // subtitle that's still starting up, and a host-only control tapped by a
    // non-host. Reusing "coming soon" for these misled users.
    val subtitlesProcessing = stringResource(R.string.room_subtitles_processing)
    val showSubtitlesProcessing: () -> Unit = {
        android.widget.Toast.makeText(context, subtitlesProcessing, android.widget.Toast.LENGTH_SHORT).show()
    }
    val hostOnly = stringResource(R.string.room_host_only)
    val showHostOnly: () -> Unit = {
        android.widget.Toast.makeText(context, hostOnly, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Sheet-specific styling: the default ControlButton colours target the
    // dark in-meeting overlay (white label, semi-transparent white bg). On
    // the light ModalBottomSheet surface those become unreadable, so we feed
    // surface-aware colours here.
    val sheetBg = MaterialTheme.colorScheme.surfaceVariant
    val sheetTint = MaterialTheme.colorScheme.onSurface
    val shareTint = if (localScreenSharing) WeMeetTheme.extras.status.danger else sheetTint

    // Raised-hand visual: orange tint while raised, neutral while lowered.
    // Matches the desktop client's "primaryDark" selected state in spirit
    // without bringing in another colour role.
    val handTint = if (handRaised) WeMeetTheme.extras.status.warning else sheetTint
    val handBg = if (handRaised) WeMeetTheme.extras.status.warningContainer else sheetBg

    // Subtitles — repurposes the old "interpret" slot. Anyone in the room
    // can flip them on; first-on POSTs start-subtitle (LiveKit-token auth,
    // see RoomRepository.startSubtitle). Once started the agent stays
    // attached for the meeting; subsequent taps only show/hide the overlay.
    val subtitlesTint = if (subtitlesOverlayOn) WeMeetTheme.extras.status.accentActive else sheetTint
    val subtitlesBg = if (subtitlesOverlayOn) WeMeetTheme.extras.status.accentActiveContainer else sheetBg

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 5-per-row grid. Each cell is `weight(1f)` so columns line up
        // between rows — keeps Settings (row 2 col 1) visually under
        // Raise Hand (row 1 col 1) without manual width math.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXl),
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
                    comingSoon = true,
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
                    onClick = if (subtitlesPending) showSubtitlesProcessing else onSubtitlesClick,
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
                    onClick = if (isAdmin) onHostSettingsClick else showHostOnly,
                    labelColor = sheetTint,
                    iconBgColor = sheetBg,
                    iconTintColor = sheetTint,
                )
                // P5(实测问题3): 「邀请成员」入口移到参会人页(搜索框旁的
                // 〔邀请〕钮,统一邀请面板),More 面板不再重复放置。
                // Reserve empty cells so Settings keeps the col-1
                // position when more entries land on row 2 later.
                Spacer(Modifier.weight(4f))
            }
        }
        Spacer(Modifier.height(Dimens.SpaceXl))
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
                comingSoon = true,
            )
            Spacer(Modifier.height(Dimens.SpaceS))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.MinTouchTarget),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.CornerM),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
        }
    }
}

@Composable
private fun ShareOptionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    comingSoon: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.SpaceL)
            .alpha(if (comingSoon) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (comingSoon) {
            Text(
                text = stringResource(R.string.room_badge_coming_soon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.CornerS))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs),
            )
        }
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
    var confirmEnd by remember { mutableStateOf(false) }

    // 结束会议对全员破坏性且不可撤销,先二次确认再执行。
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnd = false
                        onEndMeeting()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.room_end_meeting),
                        color = WeMeetTheme.extras.status.danger,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = { Text(stringResource(R.string.room_end_meeting_confirm)) },
        )
    }

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
                    modifier = Modifier.padding(bottom = Dimens.SpaceL),
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
                    onClick = { confirmEnd = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.ButtonHeight),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = WeMeetTheme.extras.status.danger,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.room_end_meeting),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceS))

            // Cancel
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.MinTouchTarget),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.CornerM),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
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
            Spacer(Modifier.height(Dimens.SpaceS))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.MinTouchTarget),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.CornerM),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(Dimens.SpaceXl))
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
            .padding(vertical = Dimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) WeMeetTheme.extras.status.accentActive
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) WeMeetTheme.extras.status.accentActive
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = WeMeetTheme.extras.status.accentActive,
                modifier = Modifier.size(Dimens.IconMedium),
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
    /** Unimplemented stub: dim the button and show a "开发中" badge so it no
     *  longer looks fully available before the "coming soon" toast fires. */
    comingSoon: Boolean = false,
    labelColor: Color = Color.White,
    iconBgColor: Color? = null,
    iconTintColor: Color? = null,
    iconSize: Dp = RoomToolbarIconSize,
    iconButtonSize: Dp = RoomToolbarIconButtonSize,
    labelSpacing: Dp = Dimens.SpaceXs,
) {
    // Default: transparent icon bg -> flat icon + label. Sheet callers pass
    // an explicit [iconBgColor] when they want the circular icon container.
    val bgColor = iconBgColor ?: Color.Transparent
    val iconTint = iconTintColor
        ?: if (isOn) Color.White else WeMeetTheme.extras.room.dangerOnOverlay

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
            .padding(vertical = Dimens.SpaceXs)
            // Dim stub controls so they don't read as fully available.
            .alpha(if (comingSoon) 0.55f else 1f),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
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
            if (comingSoon) {
                Text(
                    text = stringResource(R.string.room_badge_coming_soon),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.CornerS))
                        .background(WeMeetTheme.extras.room.overlayScrim)
                        .padding(horizontal = Dimens.SpaceXs, vertical = Dimens.SpaceXxs),
                )
            }
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
private fun ConnectingView(onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(Dimens.SpaceM))
            Text(stringResource(R.string.room_connecting), color = Color.White)
            // A stuck connect otherwise strands the user with only system Back.
            Spacer(Modifier.height(Dimens.SpaceXl))
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = Color.White)
            }
        }
    }
}

@Composable
private fun ErrorView(message: String?, onRetry: () -> Unit, onLeave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.SpaceXl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message ?: stringResource(R.string.error_unknown),
                color = WeMeetTheme.extras.room.dangerOnOverlay,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.SpaceL))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                // A failed join is often a transient network blip — offer retry
                // before forcing the user to leave and rejoin from scratch.
                Button(onClick = onRetry) {
                    Text(stringResource(DesignR.string.common_retry))
                }
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WeMeetTheme.extras.room.dangerFill,
                        contentColor = WeMeetTheme.extras.room.onDangerFill,
                    ),
                ) {
                    Text(
                        stringResource(R.string.room_action_hangup),
                        color = WeMeetTheme.extras.room.onDangerFill,
                    )
                }
            }
        }
    }
}
