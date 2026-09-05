package com.we.meet.feature.assistant.aicall.ui

import androidx.annotation.StringRes
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.feature.assistant.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus
import com.we.meet.feature.assistant.aicall.model.ConnectingStep
import com.we.meet.feature.assistant.aicall.ui.components.AnimatedSphere
import com.we.meet.feature.assistant.aicall.ui.components.BottomControls
import com.we.meet.feature.assistant.aicall.ui.components.VideoPreview
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import kotlinx.coroutines.launch

private enum class PendingPermAction { Start, ToggleVideo }

/**
 * Public entry for the realtime AI call, hosted as a full-screen secondary
 * route under the host's AI tab. [onBack] returns to the AI hub; leaving the
 * screen (back arrow / system back) ends any in-progress call.
 *
 * [deps] supplies the host's authenticated networking so the assistant
 * reuses the host session instead of its own login.
 */
@Composable
fun AssistantCallScreen(
    deps: AssistantDeps,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: AiCallViewModel = viewModel(
        factory = AiCallViewModel.Factory(context.applicationContext, deps),
    )
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Leaving the call screen ends the call (covers back arrow + system back).
    // Tied to explicit navigation rather than composable disposal so a config
    // change (rotation) doesn't tear down an active call.
    fun endAndBack() {
        vm.endCall()
        onBack()
    }
    BackHandler(enabled = true) { endAndBack() }

    var pendingAction by remember { mutableStateOf<PendingPermAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        when (pendingAction) {
            PendingPermAction.Start -> {
                pendingAction = null
                val needCamera = state.mode == AiCallMode.Video
                if (micGranted && (!needCamera || cameraGranted)) {
                    vm.startCall()
                } else {
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.assistant_need_mic_camera)) }
                }
            }
            PendingPermAction.ToggleVideo -> {
                pendingAction = null
                // Only hits this branch when going Voice → Video.
                if (cameraGranted) vm.toggleMode()
                else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.assistant_need_camera)) }
            }
            null -> Unit
        }
    }

    fun launchWithPerms() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (state.mode == AiCallMode.Video &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            vm.startCall()
        } else {
            pendingAction = PendingPermAction.Start
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun handleToggleVideo() {
        val goingToVideo = state.mode == AiCallMode.Voice
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (goingToVideo && !cameraGranted) {
            pendingAction = PendingPermAction.ToggleVideo
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else {
            vm.toggleMode()
        }
    }

    // End call on backgrounding (v1 behaviour).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val st = vm.state.value.status
                if (st is AiCallStatus.Active || st is AiCallStatus.Connecting) {
                    vm.endCall()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Surface transient toast as a snackbar.
    LaunchedEffect(state.errorToastRes) {
        val resId = state.errorToastRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(resId))
        vm.dismissError()
    }

    // Consume Ended → collapse to Idle once the UI has rendered Ended for a beat.
    LaunchedEffect(state.status) {
        if (state.status is AiCallStatus.Ended || state.status is AiCallStatus.Failed) {
            kotlinx.coroutines.delay(400)
            vm.consumeEnded()
        }
    }

    val isVideoActive = state.status is AiCallStatus.Active && state.mode == AiCallMode.Video

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Theme-aware background; the previous hardcoded white looked broken
        // in dark mode (black title text on a forced-white rectangle).
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Background — video fill in video-active mode, otherwise white.
            if (isVideoActive) {
                VideoPreview(
                    room = vm.liveKitRoom,
                    track = vm.localVideoTrack,
                    mirror = state.cameraFront,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(modifier = Modifier.fillMaxSize().padding(inner)) {
                TopBar(
                    onBack = { endAndBack() },
                    onOpenSettings = { vm.showPicker(true) },
                    canOpenSettings = state.status is AiCallStatus.Idle ||
                        state.status is AiCallStatus.Failed ||
                        state.status is AiCallStatus.Ended,
                    tintOnDark = isVideoActive,
                    showFlipCamera = isVideoActive,
                    onFlipCamera = vm::flipCamera,
                )

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isVideoActive -> Unit // video fills the background
                        else -> AnimatedSphere(
                            audioLevel = { state.agentAudioLevel },
                            onTap = vm::onTapToInterrupt,
                        )
                    }
                }

                StatusHint(
                    status = state.status,
                    mode = state.mode,
                    onDark = isVideoActive,
                )

                BottomControls(
                    status = state.status,
                    mode = state.mode,
                    isMicMuted = state.isMicMuted,
                    micPending = state.micPending,
                    onToggleMic = vm::toggleMic,
                    onPrimaryAction = {
                        when (state.status) {
                            is AiCallStatus.Idle,
                            is AiCallStatus.Failed,
                            is AiCallStatus.Ended,
                            -> launchWithPerms()
                            else -> vm.endCall()
                        }
                    },
                    onToggleVideoMode = { handleToggleVideo() },
                    onDark = isVideoActive,
                )

                Text(
                    text = stringResource(R.string.assistant_ai_generated),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isVideoActive) WeMeetTheme.extras.aiCall.onVideo.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Dimens.SpaceL),
                    textAlign = TextAlign.Center,
                )
            }

            if (state.showPicker) {
                AiSettingsSheet(
                    config = state.agentConfig,
                    currentMode = state.mode,
                    voiceSelection = state.voiceSelection,
                    videoSelection = state.videoSelection,
                    onModeChange = vm::setMode,
                    onSelectProfile = vm::selectProfile,
                    onSelectVoice = vm::selectVoice,
                    onSelectPrompt = vm::selectPrompt,
                    onDismiss = { vm.showPicker(false) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    canOpenSettings: Boolean,
    tintOnDark: Boolean,
    showFlipCamera: Boolean,
    onFlipCamera: () -> Unit,
) {
    // tintOnDark = true only when video fills the background (so the bar
    // sits on the camera feed). Otherwise the bar sits on the theme
    // background and should follow it (dark surface → light text).
    val tint = if (tintOnDark) WeMeetTheme.extras.aiCall.onVideo
        else MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceS),
    ) {
        // Leading back + trailing (flip?, settings) on the same row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.assistant_cd_back),
                    tint = tint,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (showFlipCamera) {
                IconButton(onClick = onFlipCamera) {
                    Icon(
                        imageVector = Icons.Filled.FlipCameraIos,
                        contentDescription = stringResource(R.string.assistant_cd_switch_camera),
                        tint = tint,
                    )
                }
            }
            IconButton(onClick = onOpenSettings, enabled = canOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.assistant_cd_settings),
                    tint = if (canOpenSettings) tint else tint.copy(alpha = 0.4f),
                )
            }
        }
        // Centred title, overlaid so it stays centred regardless of the
        // asymmetric leading/trailing icon counts.
        Text(
            text = stringResource(R.string.assistant_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun StatusHint(
    status: AiCallStatus,
    mode: AiCallMode,
    onDark: Boolean,
) {
    val (label, isConnecting) = when (status) {
        is AiCallStatus.Idle -> (if (mode == AiCallMode.Voice) stringResource(R.string.assistant_tap_to_start_voice)
            else stringResource(R.string.assistant_tap_to_start_video)) to false
        is AiCallStatus.Connecting -> stringResource(connectingLabelRes(status.step)) to true
        is AiCallStatus.Active -> (if (status.mode == AiCallMode.Voice) stringResource(R.string.assistant_speak_or_interrupt)
            else stringResource(R.string.assistant_listening)) to false
        is AiCallStatus.Ended -> stringResource(R.string.assistant_call_ended) to false
        is AiCallStatus.Failed -> status.message to false
    }
    val background = if (onDark) WeMeetTheme.extras.aiCall.videoScrim
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (onDark) WeMeetTheme.extras.aiCall.onVideo
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceM),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.CornerL))
                .background(background)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.IconTiny),
                    strokeWidth = Dimens.BorderEmphasis,
                    color = textColor,
                )
                Spacer(modifier = Modifier.size(Dimens.SpaceS))
            }
            Text(text = label, color = textColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 只做「步骤 → 文案资源」的映射,解析交给调用方 —— 保持它是个纯函数。 */
@StringRes
private fun connectingLabelRes(step: ConnectingStep): Int = when (step) {
    ConnectingStep.CreatingRoom -> R.string.assistant_step_creating_room
    ConnectingStep.JoiningLiveKit -> R.string.assistant_step_joining
    ConnectingStep.PublishingTracks -> R.string.assistant_step_publishing
    ConnectingStep.StartingAgent -> R.string.assistant_step_starting_agent
    ConnectingStep.WaitingAgent -> R.string.assistant_step_waiting_agent
    ConnectingStep.SwitchingMode -> R.string.assistant_step_switching_mode
}
