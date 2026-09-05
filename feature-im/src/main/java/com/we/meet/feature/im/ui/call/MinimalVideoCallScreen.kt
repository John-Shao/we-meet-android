package com.we.meet.feature.im.ui.call

import com.we.meet.ui.theme.WeMeetTheme
import com.we.meet.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.we.meet.feature.im.ImDeps
import com.we.meet.feature.im.ImSession
import com.we.meet.feature.im.R
import com.we.meet.feature.im.data.GroupTile
import com.we.meet.feature.im.ui.common.GroupAvatar
import kotlinx.coroutines.delay

/**
 * WeChat-style minimal in-call screen for a 1:1 **video** call — full-screen
 * remote video (peer avatar + name while their camera is off), a small local
 * self-view pinned top-right, duration at the top, and the same rounded
 * control buttons as [MinimalVoiceCallScreen]: mic / speaker⇌earpiece /
 * camera / flip on one row, a red hangup below.
 *
 * Pure UI like the voice screen: the LiveKit room lives in the app module,
 * so the two video surfaces come in as slots ([remoteVideo]/[localVideo],
 * null when the corresponding camera track is absent) and every action is an
 * injected callback.
 */
@Composable
fun MinimalVideoCallScreen(
    deps: ImDeps,
    peerUid: String,
    fallbackName: String,
    connected: Boolean,
    micEnabled: Boolean,
    onToggleMic: () -> Unit,
    speakerOn: Boolean,
    onToggleSpeaker: () -> Unit,
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onHangup: () -> Unit,
    remoteVideo: (@Composable () -> Unit)?,
    localVideo: (@Composable () -> Unit)?,
    /** P4: opens the escalation picker; sending an invite flips the room to
     * the full meeting UI (RoomScreen's upgrade latch). */
    onAddMember: (() -> Unit)? = null,
) {
    val session = remember(deps) { ImSession.get(deps) }
    LaunchedEffect(peerUid) { session.userDirectory.requestResolve(listOf(peerUid)) }
    val directoryVersion by session.userDirectory.version.collectAsStateWithLifecycle()
    val peer = remember(peerUid, directoryVersion) { session.userDirectory.get(peerUid) }
    val displayName = peer?.displayName?.takeIf { it.isNotBlank() } ?: fallbackName

    // Same anchored duration clock as the voice screen.
    var connectedAt by remember { mutableLongStateOf(0L) }
    var elapsedSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
        while (true) {
            elapsedSec = (System.currentTimeMillis() - connectedAt) / 1000
            delay(1000)
        }
    }
    val status = if (connected) formatElapsed(elapsedSec)
    else stringResource(R.string.im_call_connecting)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VideoStageBackground),
    ) {
        if (remoteVideo != null) {
            remoteVideo()
            // Duration pill floating over the video, top center.
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = VideoStageLabel,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = Dimens.SpaceM)
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f),
                        RoundedCornerShape(Dimens.CornerM),
                    )
                    .padding(horizontal = Dimens.SpaceM, vertical = Dimens.SpaceXs),
            )
        } else {
            // Peer camera off / not joined yet — voice-screen style center block.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = Dimens.SpaceXxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GroupAvatar(
                    tiles = listOf(GroupTile(peerUid, displayName, peer?.avatarUrl)),
                    size = Dimens.Chat.CallAvatarSize,
                )
                Spacer(Modifier.height(Dimens.SpaceXl))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = VideoStageLabel,
                )
                Spacer(Modifier.height(Dimens.SpaceM))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VideoStageLabel.copy(alpha = 0.7f),
                )
            }
        }

        // Local self-view, top-right (hidden while our camera is off — the
        // camera button already tells the user their video is not sending).
        if (localVideo != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = Dimens.SpaceM, end = Dimens.ScreenPadding)
                    .size(width = Dimens.Chat.PipWidth, height = Dimens.Chat.PipHeight)
                    .clip(RoundedCornerShape(Dimens.CornerM))
                    .background(VideoStageBackground),
            ) {
                localVideo()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = Dimens.SpaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RoundButton(
                    icon = if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    label = stringResource(
                        if (micEnabled) R.string.im_call_mic_on else R.string.im_call_mic_off
                    ),
                    background = if (micEnabled) NeutralControl else MutedControl,
                    onClick = onToggleMic,
                    labelColor = VideoStageLabel,
                )
                RoundButton(
                    icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing,
                    label = stringResource(
                        if (speakerOn) R.string.im_call_speaker else R.string.im_call_earpiece
                    ),
                    background = if (speakerOn) AcceptGreen else NeutralControl,
                    onClick = onToggleSpeaker,
                    labelColor = VideoStageLabel,
                )
                RoundButton(
                    icon = if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = stringResource(
                        if (cameraEnabled) R.string.im_call_camera_on else R.string.im_call_camera_off
                    ),
                    background = if (cameraEnabled) NeutralControl else MutedControl,
                    onClick = onToggleCamera,
                    labelColor = VideoStageLabel,
                )
                RoundButton(
                    icon = Icons.Filled.Cameraswitch,
                    label = stringResource(R.string.im_call_flip),
                    background = NeutralControl,
                    onClick = onFlipCamera,
                    labelColor = VideoStageLabel,
                )
                if (onAddMember != null) {
                    RoundButton(
                        icon = Icons.Filled.PersonAdd,
                        label = stringResource(R.string.im_call_add_member),
                        background = NeutralControl,
                        onClick = onAddMember,
                        labelColor = VideoStageLabel,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.SpaceXxl))
            RoundButton(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.im_call_hangup),
                background = HangupRed,
                onClick = onHangup,
                labelColor = VideoStageLabel,
            )
        }
    }
}

/** The video stage is always dark (video behind), regardless of app theme. */

/** 视频通话舞台配色,同 [HangupRed] 一组的取值方式。 */
private val VideoStageBackground: Color
    @Composable get() = WeMeetTheme.extras.im.videoStageBackground
private val VideoStageLabel: Color
    @Composable get() = WeMeetTheme.extras.im.videoStageLabel
