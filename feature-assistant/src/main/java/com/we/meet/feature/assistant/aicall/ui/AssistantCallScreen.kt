package com.we.meet.feature.assistant.aicall.ui

import androidx.annotation.StringRes
import com.we.meet.ui.theme.Dimens
import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.feature.assistant.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.feature.assistant.AssistantDeps
import com.we.meet.feature.assistant.aicall.rtc.AoqPlaybackMode
import com.we.meet.feature.assistant.aicall.model.AiCallTransport
import com.we.meet.feature.assistant.aicall.model.AiCallMode
import com.we.meet.feature.assistant.aicall.model.AiCallStatus
import com.we.meet.feature.assistant.aicall.model.ConnectingStep
import com.we.meet.feature.assistant.aicall.ui.components.AnimatedSphere
import com.we.meet.feature.assistant.aicall.ui.components.BottomControls
import com.we.meet.feature.assistant.aicall.ui.components.VideoPreview
import com.we.meet.feature.assistant.aicall.vm.AiCallViewModel
import com.we.meet.ui.components.WeMeetTopBar
import kotlinx.coroutines.launch

private enum class PendingPermAction { Start, ToggleVideo }

private tailrec fun Context.callActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.callActivity()
    else -> null
}

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: AiCallViewModel = viewModel(
        factory = AiCallViewModel.Factory(context.applicationContext, deps),
    )
    val state by vm.state.collectAsState()
    DisposableEffect(vm, lifecycleOwner) {
        fun updateVisibility() { vm.setPageVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateVisibility()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.setPageVisible(false) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activity = remember(context) { context.callActivity() }
    // Only an actual foreground video call holds the screen awake.
    val keepVideoScreenOn = state.status is AiCallStatus.Active && state.isCameraEnabled
    DisposableEffect(activity, lifecycleOwner, keepVideoScreenOn) {
        val window = activity?.window
        val screenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val previouslyKeptOn = window?.attributes?.flags?.let { it and screenOnFlag != 0 } ?: false
        fun updateScreenOn() {
            val keepOn = keepVideoScreenOn &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (keepOn || previouslyKeptOn) window?.addFlags(screenOnFlag)
            else window?.clearFlags(screenOnFlag)
        }
        val observer = LifecycleEventObserver { _, _ -> updateScreenOn() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateScreenOn()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (previouslyKeptOn) window?.addFlags(screenOnFlag)
            else window?.clearFlags(screenOnFlag)
        }
    }
    // Keep volume keys on the actual playback stream, including during silence.
    val callInProgress = state.status is AiCallStatus.Connecting || state.status is AiCallStatus.Active
    val playbackStream = if (state.selection.transport == AiCallTransport.AOQ)
        AoqPlaybackMode.volumeStream else AudioManager.STREAM_VOICE_CALL
    DisposableEffect(activity, callInProgress, playbackStream) {
        val previousStream = activity?.volumeControlStream
        if (callInProgress) activity?.volumeControlStream = playbackStream
        onDispose {
            if (callInProgress && previousStream != null) {
                activity?.volumeControlStream = previousStream
            }
        }
    }

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
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@rememberLauncherForActivityResult
                if (micGranted && (!needCamera || cameraGranted)) {
                    vm.startCall()
                } else {
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.assistant_need_mic_camera)) }
                }
            }
            PendingPermAction.ToggleVideo -> {
                pendingAction = null
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@rememberLauncherForActivityResult
                // Only hits this branch when going Voice → Video.
                if (cameraGranted) vm.selectMode(AiCallMode.Video)
                else scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.assistant_need_camera)) }
            }
            null -> Unit
        }
    }

    var launchedCameraPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = launchedCameraPermissionId
        launchedCameraPermissionId = null
        if (id != null) vm.cameraPermissionResult(id, granted)
    }
    LaunchedEffect(state.cameraPermissionRequest?.id, launchedCameraPermissionId) {
        val request = state.cameraPermissionRequest ?: return@LaunchedEffect
        if (launchedCameraPermissionId == null) {
            launchedCameraPermissionId = request.id
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            vm.startCall()
        } else {
            pendingAction = PendingPermAction.Start
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun handleToggleVideo() {
        if (state.status is AiCallStatus.Active) { vm.toggleMode(); return }
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

    // Home and screen lock keep the foreground-service-backed call alive.

    // Surface transient toast as a snackbar.
    LaunchedEffect(state.errorToastRes) {
        val resId = state.errorToastRes ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(resId))
        vm.dismissError()
    }

    // Consume Ended → collapse to Idle once the UI has rendered Ended for a beat.
    LaunchedEffect(state.status) {
        if (state.status is AiCallStatus.Ended) {
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
        topBar = {
            TopBar(
                onBack = { endAndBack() },
                onOpenSettings = { vm.showPicker(true) },
                canOpenSettings = state.status is AiCallStatus.Idle ||
                    state.status is AiCallStatus.Failed ||
                    state.status is AiCallStatus.Ended,
                isOutputMuted = state.isOutputMuted,
                canToggleOutput = state.status is AiCallStatus.Active,
                onToggleOutput = vm::toggleOutput,
                tintOnDark = isVideoActive,
                showFlipCamera = isVideoActive,
                onFlipCamera = vm::flipCamera,
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Background — video fill in video-active mode, otherwise white.
            if (isVideoActive) {
                VideoPreview(
                    client = vm.rtcClient,
                    mirror = state.cameraFront,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(modifier = Modifier.fillMaxSize().padding(inner)) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.status is AiCallStatus.Active) {
                        Text(
                            text = state.selection.transport.name,
                            color = if (isVideoActive) WeMeetTheme.extras.aiCall.onVideo
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(top = Dimens.SpaceS, end = Dimens.ScreenPadding)
                                .clip(RoundedCornerShape(Dimens.CornerS))
                                .background(if (isVideoActive) WeMeetTheme.extras.aiCall.videoScrim
                                    else MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
                        )
                    }
                    if (!isVideoActive) {
                        AnimatedSphere(
                            audioLevel = { state.agentAudioLevel },
                            contentDescription = stringResource(R.string.assistant_cd_interrupt),
                            enabled = state.status is AiCallStatus.Active,
                            onTap = vm::onTapToInterrupt,
                        )
                    }
                }

                StatusHint(
                    status = state.status,
                    mode = state.mode,
                    onDark = isVideoActive,
                )

                if (state.status is AiCallStatus.Active && (state.cameraPending || state.cameraResult?.success == false)) {
                    Text(if (state.cameraPending) stringResource(R.string.assistant_camera_working) else state.cameraResult!!.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isVideoActive) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = Dimens.SpaceL))
                }

                BottomControls(
                    status = state.status,
                    mode = state.mode,
                    isMicMuted = state.isMicMuted,
                    micPending = state.micPending,
                    cameraPending = state.cameraPending,
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
                    selection = state.selection,
                    historyStore = vm.history,
                    historyEnabled = !callInProgress,
                    onSelectTransport = vm::selectTransport,
                    onSelectVoice = vm::selectVoice,
                    onSelectPrompt = vm::selectPrompt,
                    onSelectScene = vm::selectScene,
                    onDismiss = { vm.showPicker(false) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    canOpenSettings: Boolean,
    isOutputMuted: Boolean,
    canToggleOutput: Boolean,
    onToggleOutput: () -> Unit,
    tintOnDark: Boolean,
    showFlipCamera: Boolean,
    onFlipCamera: () -> Unit,
) {
    val tint = if (tintOnDark) WeMeetTheme.extras.aiCall.onVideo
        else MaterialTheme.colorScheme.onSurfaceVariant
    WeMeetTopBar(
        title = stringResource(R.string.assistant_history_call),
        onBack = onBack,
        colors = if (tintOnDark) TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = tint,
            navigationIconContentColor = tint,
            actionIconContentColor = tint,
        ) else null,
        actions = {
            if (showFlipCamera) {
                IconButton(onClick = onFlipCamera) {
                    Icon(
                        imageVector = Icons.Filled.FlipCameraIos,
                        contentDescription = stringResource(R.string.assistant_cd_switch_camera),
                        tint = tint,
                    )
                }
            }
            val playbackState = stringResource(if (isOutputMuted)
                R.string.assistant_background_muted else R.string.assistant_background_sound)
            IconToggleButton(
                checked = isOutputMuted,
                onCheckedChange = { onToggleOutput() },
                enabled = canToggleOutput,
                modifier = Modifier.semantics { stateDescription = playbackState },
            ) {
                Icon(
                    imageVector = if (isOutputMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(if (isOutputMuted)
                        R.string.assistant_background_unmute else R.string.assistant_background_mute),
                    tint = if (canToggleOutput) tint else tint.copy(alpha = 0.4f),
                )
            }
            IconButton(onClick = onOpenSettings, enabled = canOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.assistant_cd_settings),
                    tint = if (canOpenSettings) tint else tint.copy(alpha = 0.4f),
                )
            }
        },
    )
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
    ConnectingStep.Connecting -> R.string.assistant_step_connecting
    ConnectingStep.Configuring -> R.string.assistant_step_configuring
}
