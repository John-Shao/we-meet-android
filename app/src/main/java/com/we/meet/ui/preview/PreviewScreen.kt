package com.we.meet.ui.preview

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.we.meet.ui.components.WeMeetTopBar
import com.we.meet.WeMeetApp
import com.we.meet.R
import com.we.meet.ui.theme.Dimens
import com.we.meet.audio.AudioOutput
import com.we.meet.audio.AudioOutputController
import com.we.meet.audio.AudioOutputStore

enum class PreviewMode { Create, Join }

/**
 * Pre-meeting preview screen used for both creating and joining meetings.
 *
 * - **Create mode**: editable meeting name, camera preview, "开始会议" button.
 * - **Join mode**: meeting ID input, camera preview, "加入会议" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    mode: PreviewMode,
    onEnterRoom: (roomId: String, livekitUrl: String, livekitToken: String, name: String, slug: String, host: String?, createdAtMs: Long, isAdmin: Boolean, mic: Boolean, cam: Boolean) -> Unit,
    onClose: () -> Unit,
    /**
     * When non-null, seeds the meeting-id input in Join mode (used by App
     * Links deep links so the user lands on a Preview with the code already
     * filled in). Ignored in Create mode.
     */
    initialMeetingId: String? = null,
    /**
     * When non-null, seeds the editable meeting-name field in Create mode
     * (used when 发起会议 came from a group chat, so the name defaults to
     * "{群名}的视频会议"). Falls back to [PreviewViewModel.defaultMeetingName]
     * when null. Ignored in Join mode.
     */
    initialMeetingName: String? = null,
    /**
     * Create mode only: start with the camera off and hide its toggle, for
     * 语音通话 launched from a 1:1 chat. The user still gets mic + speaker
     * controls; it's an audio call, not a locked-down one. Ignored in Join mode.
     */
    audioOnly: Boolean = false,
    /**
     * Fired in Join mode when the target room exists but the current
     * user can't access it directly (typically access_level=restricted).
     * AppNav should navigate to the waiting-room screen, passing the
     * idOrSlug + display name forward. Ignored in Create mode.
     */
    onNeedsLobby: (idOrSlug: String, roomName: String, mic: Boolean, cam: Boolean) -> Unit = { _, _, _, _ -> },
) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val previewViewModel: PreviewViewModel = viewModel(factory = PreviewViewModel.Factory(app))
    val state by previewViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val audioOutputController = remember(context) { AudioOutputController(context) }

    // Permission handling.
    //
    // `required` gates the camera preview + join action. The extras in
    // `requested` are best-effort — denying them must NOT block joining:
    //   - BLUETOOTH_CONNECT (API 31+): needed for
    //     AudioManager.availableCommunicationDevices to include BT headsets
    //     so the "Earpiece" route can reach a paired headset.
    //   - POST_NOTIFICATIONS (API 33+): lets the in-meeting foreground
    //     service show its "meeting in progress" notification. The FGS
    //     itself still runs without it (and keeps cam/mic alive in
    //     background); user just won't see the notification.
    val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    val requested = buildList {
        addAll(required)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    // Mic gates joining (without it you'd be a silent "ghost"); camera can be
    // off and you still join. Tracked separately so the join action isn't
    // blocked merely because the camera was denied.
    var micGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    // Whether we've already prompted — distinguishes "not asked yet" from
    // "asked and denied", so we only show the denied guidance after a prompt.
    var permissionAsked by remember { mutableStateOf(false) }
    val permissionsGranted = micGranted && cameraGranted
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permissionAsked = true
        micGranted = granted(Manifest.permission.RECORD_AUDIO)
        cameraGranted = granted(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        val missing = requested.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permissionLauncher.launch(requested)
    }

    // Re-read on resume so granting in system Settings (via "Open settings")
    // and returning immediately reflects here instead of staying stuck.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        micGranted = granted(Manifest.permission.RECORD_AUDIO)
        cameraGranted = granted(Manifest.permission.CAMERA)
        onPauseOrDispose {}
    }

    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(!audioOnly) }
    var audioOutput by remember { mutableStateOf(AudioOutputStore.lastChoice) }
    var showAudioSheet by remember { mutableStateOf(false) }

    DisposableEffect(audioOutputController) {
        audioOutputController.start()
        onDispose { audioOutputController.stop() }
    }
    LaunchedEffect(audioOutput) {
        AudioOutputStore.lastChoice = audioOutput
        audioOutputController.apply(audioOutput)
    }

    // Mode-specific state
    var meetingName by remember {
        mutableStateOf(initialMeetingName?.takeIf { it.isNotBlank() } ?: previewViewModel.defaultMeetingName)
    }
    var meetingId by remember { mutableStateOf(initialMeetingId.orEmpty()) }

    val doAction: () -> Unit = {
        val callback = { target: RoomTarget ->
            onEnterRoom(target.roomId, target.livekitUrl, target.livekitToken, target.displayName, target.slug, target.host, target.createdAtMs, target.isAdmin, micEnabled, cameraEnabled)
        }
        when (mode) {
            PreviewMode.Create -> previewViewModel.createMeeting(meetingName, callback)
            PreviewMode.Join -> previewViewModel.joinRoom(
                slug = meetingId,
                onSuccess = callback,
                onNeedsLobby = { idOrSlug, roomName ->
                    onNeedsLobby(idOrSlug, roomName, micEnabled, cameraEnabled)
                },
            )
        }
    }

    // Auto-dismiss the inline error banner when the user edits the input.
    LaunchedEffect(meetingName, meetingId) {
        previewViewModel.dismissError()
    }

    var closePending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            WeMeetTopBar(
                title = "",
                onBack = { if (!closePending) { closePending = true; onClose() } },
                transparent = true,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Dimens.SpaceS))

            // Header: editable meeting name (create) or meeting ID input (join)
            when (mode) {
                PreviewMode.Create -> {
                    OutlinedTextField(
                        value = meetingName,
                        onValueChange = { meetingName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXxxl),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                PreviewMode.Join -> {
                    OutlinedTextField(
                        value = meetingId,
                        onValueChange = { value ->
                            // Only allow digits, max 8 characters (slug is an
                            // 8-digit numeric meeting code on the we-meet backend).
                            meetingId = value.filter { it.isDigit() }.take(8)
                        },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.preview_meeting_id_hint),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = Dimens.SpaceXxxl),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceL))

            // Camera preview area. `weight(1f)` lets it absorb whatever
            // vertical space is left after the text field, toggle row,
            // banner slot, and action button — so the camera is as large
            // as it can be without pushing anything off-screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.CornerL))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Any required permission denied after we prompted: show a
                    // distinct message + a way out, instead of the ambiguous
                    // "No video" that also means "camera simply off".
                    permissionAsked && !permissionsGranted -> PermissionDeniedContent(
                        onOpenSettings = { openAppSettings(context) },
                    )
                    cameraEnabled && cameraGranted -> CameraPreview()
                    else -> Text(
                        text = stringResource(R.string.room_no_video),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.SpaceL))

            // Mic / Camera / Speaker toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM, Alignment.CenterHorizontally),
            ) {
                ToggleCard(
                    icon = if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    label = stringResource(R.string.preview_mic),
                    isOn = micEnabled,
                    onClick = { micEnabled = !micEnabled },
                    modifier = Modifier.weight(1f),
                )
                // Hidden for 语音通话 — an audio call keeps the camera off and
                // out of reach; mic + speaker remain.
                if (!audioOnly) {
                    // Reflect effective state: with camera permission denied the
                    // card must not read "on" while the preview shows no video.
                    val cameraOn = cameraEnabled && cameraGranted
                    ToggleCard(
                        icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        label = stringResource(R.string.preview_camera),
                        isOn = cameraOn,
                        onClick = {
                            if (!cameraGranted) openAppSettings(context) else cameraEnabled = !cameraEnabled
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                ToggleCard(
                    icon = when (audioOutput) {
                        AudioOutput.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
                        AudioOutput.Earpiece -> Icons.Default.Hearing
                        AudioOutput.Mute -> Icons.AutoMirrored.Filled.VolumeOff
                    },
                    label = stringResource(when (audioOutput) {
                        AudioOutput.Speaker -> R.string.preview_speaker
                        AudioOutput.Earpiece -> R.string.preview_earpiece
                        AudioOutput.Mute -> R.string.preview_mute
                    }),
                    isOn = audioOutput != AudioOutput.Mute,
                    onClick = { showAudioSheet = true },
                    modifier = Modifier.weight(1f),
                )
            }

            // Inline error banner — shown just above the primary action so
            // the user reads the error and reaches the button in one vertical
            // glance. The slot is always reserved (even when empty) so the
            // button position never shifts when the banner appears. Dismisses
            // automatically when the input changes.
            ErrorBanner(
                message = state.errorMessage,
                onDismiss = previewViewModel::dismissError,
            )

            // Action button
            val actionLabel = when (mode) {
                PreviewMode.Create -> stringResource(R.string.preview_start_meeting)
                PreviewMode.Join -> stringResource(R.string.preview_join_meeting)
            }
            // Mic permission is a hard gate for both modes: joining without it
            // makes you a silent participant with no on-screen explanation.
            val actionEnabled = micGranted && when (mode) {
                PreviewMode.Create -> meetingName.isNotBlank() && !state.isLoading
                PreviewMode.Join -> meetingId.length == 8 && !state.isLoading
            }

            Button(
                onClick = doAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.ButtonHeight),
                shape = RoundedCornerShape(Dimens.CornerM),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        strokeWidth = Dimens.BorderEmphasis,
                        modifier = Modifier.size(Dimens.IconSmall),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Dimens.SpaceS))
                }
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(Dimens.SpaceXxl))
        }
    }

    if (showAudioSheet) {
        AudioOutputSheet(
            current = audioOutput,
            onSelect = { audioOutput = it; showAudioSheet = false },
            onDismiss = { showAudioSheet = false },
        )
    }
}

/**
 * Fixed-height error slot placed between the device-control row and the
 * primary action button. The slot always occupies [BannerSlotHeight], so
 * the action button's position never shifts — the banner fades in and
 * out inside the reserved space, vertically centred with 8dp margin on
 * top and bottom.
 */
private val BannerContentHeight = Dimens.SpaceXxl
private val BannerSlotHeight = BannerContentHeight + Dimens.SpaceL

@Composable
private fun ErrorBanner(
    message: String?,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BannerSlotHeight)
            .padding(vertical = Dimens.SpaceS),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = message != null,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            // Keep the last non-null message while fading out so the
            // exit transition has something to render.
            val text = message ?: ""
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(Dimens.CornerS),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BannerContentHeight),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Dimens.SpaceM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                    Spacer(Modifier.width(Dimens.SpaceS))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(Dimens.IconMedium),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(Dimens.IconTiny),
                        )
                    }
                }
            }
        }
    }
}

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
                    .height(Dimens.IconIllustration),
                shape = RoundedCornerShape(Dimens.CornerM),
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
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Spacer(Modifier.width(Dimens.SpaceL))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconMedium),
            )
        }
    }
}

@Composable
private fun ToggleCard(
    icon: ImageVector,
    label: String,
    isOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconTint = if (isOn) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.CornerM))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.SpaceM),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(Dimens.IconMedium),
        )
        Spacer(Modifier.height(Dimens.SpaceXs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown in the preview area when a required permission was denied: a clear
 *  message plus a route into system Settings (re-requesting is a no-op once
 *  permanently denied). */
@Composable
private fun PermissionDeniedContent(onOpenSettings: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        modifier = Modifier.padding(Dimens.SpaceXl),
    ) {
        Icon(
            imageVector = Icons.Default.VideocamOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconXl),
        )
        Text(
            text = stringResource(R.string.room_permission_required),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.preview_open_settings))
        }
    }
}

/** Open this app's system settings page so the user can flip a permission
 *  that was permanently denied. */
private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun CameraPreview() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose { cameraProvider?.unbindAll() }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}
